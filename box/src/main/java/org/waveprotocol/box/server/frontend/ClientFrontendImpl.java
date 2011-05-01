/**
 * Copyright 2009 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.waveprotocol.box.server.frontend;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.MapMaker;
import com.google.common.collect.Sets;

import org.waveprotocol.box.common.CommonConstants;
import org.waveprotocol.box.common.DeltaSequence;
import org.waveprotocol.box.common.ExceptionalIterator;
import org.waveprotocol.box.common.IndexWave;
import org.waveprotocol.box.common.Snippets;
import org.waveprotocol.box.common.comms.WaveClientRpc;
import org.waveprotocol.box.server.waveserver.WaveBus;
import org.waveprotocol.box.server.waveserver.WaveServerException;
import org.waveprotocol.box.server.waveserver.WaveletProvider;
import org.waveprotocol.box.server.waveserver.WaveletProvider.SubmitRequestListener;
import org.waveprotocol.wave.federation.Proto.ProtocolWaveletDelta;
import org.waveprotocol.wave.model.id.IdFilter;
import org.waveprotocol.wave.model.id.WaveId;
import org.waveprotocol.wave.model.id.WaveletId;
import org.waveprotocol.wave.model.id.WaveletName;
import org.waveprotocol.wave.model.operation.wave.AddParticipant;
import org.waveprotocol.wave.model.operation.wave.RemoveParticipant;
import org.waveprotocol.wave.model.operation.wave.TransformedWaveletDelta;
import org.waveprotocol.wave.model.operation.wave.WaveletOperation;
import org.waveprotocol.wave.model.version.HashedVersion;
import org.waveprotocol.wave.model.version.HashedVersionFactory;
import org.waveprotocol.wave.model.wave.ParticipantId;
import org.waveprotocol.wave.model.wave.data.ReadableWaveletData;
import org.waveprotocol.wave.util.logging.Log;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * Implements the client front-end.
 *
 * This class maintains a list of wavelets accessible by local participants by
 * inspecting all updates it receives (there is no need to inspect historic
 * deltas as they would have been received as updates had there been an
 * addParticipant). Updates are aggregated in a special index Wave which is
 * stored with the WaveServer.
 *
 * When a wavelet is added and it's not at version 0, buffer updates until a
 * request for the wavelet's history has completed.
 */
public class ClientFrontendImpl implements ClientFrontend, WaveBus.Subscriber {
  private static final Log LOG = Log.get(ClientFrontendImpl.class);

  private final static AtomicInteger channel_counter = new AtomicInteger(0);
  
  /** Information we hold in memory for each wavelet, including index wavelets. */
  private static class PerWavelet {
    private final HashedVersion version0;
    private final Set<ParticipantId> participants;
    private HashedVersion currentVersion;
    private String digest;

    PerWavelet(WaveletName waveletName, HashedVersion hashedVersionZero) {
      this.participants = Collections.synchronizedSet(Sets.<ParticipantId>newHashSet());
      this.version0 = hashedVersionZero;
      this.currentVersion = version0;
      this.digest = "";
    }

    synchronized HashedVersion getCurrentVersion() {
      return currentVersion;
    }

    synchronized void setCurrentVersion(HashedVersion version) {
      this.currentVersion = version;
    }
  }

  @VisibleForTesting final Map<ParticipantId, UserManager> perUser;
  private final Map<WaveId, Map< WaveletId, PerWavelet>> perWavelet;
  private final WaveletProvider waveletProvider;

  /**
   * Creates a client frontend and subscribes it to the wave bus.
   *
   * @throws WaveServerException if the server fails during initialisation
   */
  public static ClientFrontendImpl create(HashedVersionFactory hashedVersionFactory,
      WaveletProvider waveletProvider, WaveBus wavebus)
      throws WaveServerException {
    
    ClientFrontendImpl impl =
        new ClientFrontendImpl(hashedVersionFactory, waveletProvider);

    // Initialize index here until a separate index system exists.
    impl.initialiseAllWaves();
    wavebus.subscribe(impl);
    return impl;
  }

  /**
   * Constructor.
   * 
   * @param hashedVersionFactory
   * @param waveletProvider
   * @param waveDomain the server wave domain. It is assumed that the wave domain is valid.
   */
  @VisibleForTesting
  ClientFrontendImpl(final HashedVersionFactory hashedVersionFactory,
      WaveletProvider waveletProvider) {
    this.waveletProvider = waveletProvider;
    final MapMaker mapMaker = new MapMaker();
    perWavelet = mapMaker.makeComputingMap(new Function<WaveId, Map<WaveletId, PerWavelet>>() {
      @Override
      public Map<WaveletId, PerWavelet> apply(final WaveId waveId) {
        return mapMaker.makeComputingMap(new Function<WaveletId, PerWavelet>() {
          @Override
          public PerWavelet apply(WaveletId waveletId) {
            WaveletName waveletName = WaveletName.of(waveId, waveletId);
            return new PerWavelet(waveletName, hashedVersionFactory.createVersionZero(waveletName));
          }
        });
      }
    });

    perUser = mapMaker.makeComputingMap(new Function<ParticipantId, UserManager>() {
      @Override
      public UserManager apply(ParticipantId from) {
        return new UserManager();
      }
    });
  }

  @Override
  public void openRequest(ParticipantId loggedInUser, WaveId waveId, IdFilter waveletIdFilter,
      Collection<WaveClientRpc.WaveletVersion> knownWavelets, OpenListener openListener) {
    LOG.info("received openRequest from " + loggedInUser + " for " + waveId + ", filter "
        + waveletIdFilter + ", known wavelets: " + knownWavelets);

    // TODO(josephg): Make it possible for this to succeed & return public waves.
    if (loggedInUser == null) {
      openListener.onFailure("Not logged in");
      return;
    }

    if (!knownWavelets.isEmpty()) {
      openListener.onFailure("Known wavelets not supported");
      return;
    }

    boolean isIndexWave = IndexWave.isIndexWave(waveId);
    try {
      if (!isIndexWave) {
        initialiseWave(waveId);
      }
    } catch (WaveServerException e) {
      LOG.severe("Wave server failed lookup for " + waveId, e);
      openListener.onFailure("Wave server failed to look up wave");
      return;
    }

    String channelId = generateChannelID();
    UserManager userManager = perUser.get(loggedInUser);
    synchronized (userManager) {
      WaveViewSubscription subscription =
          userManager.subscribe(waveId, waveletIdFilter, channelId, openListener);
      LOG.info("Subscribed " + loggedInUser + " to " + waveId + " channel " + channelId);

      Set<WaveletId> waveletIds;
      try {
        waveletIds = visibleWaveletsFor(subscription, loggedInUser);
      } catch (WaveServerException e1) {
        waveletIds = Sets.newHashSet();
        LOG.warning("Failed to retrieve visible wavelets for " + loggedInUser, e1);
      }
      for (WaveletId waveletId : waveletIds) {
        WaveletName waveletName = WaveletName.of(waveId, waveletId);
        // The WaveletName by which the waveletProvider knows the relevant deltas

        // TODO(anorth): if the client provides known wavelets, calculate
        // where to start sending deltas from.

        DeltaSequence deltasToSend;
        CommittedWaveletSnapshot snapshotToSend;
        HashedVersion endVersion;

        if (isIndexWave) {
          // Fetch deltas from the real wave from which the index wavelet
          // is generated.
          // TODO(anorth): send a snapshot instead.
          WaveletName sourceWaveletName = IndexWave.waveletNameFromIndexWavelet(waveletName);

          endVersion = getWavelet(sourceWaveletName).currentVersion;
          HashedVersion startVersion = getWavelet(sourceWaveletName).version0;
          try {
            // Send deltas to bring the wavelet up to date
            DeltaSequence sourceWaveletDeltas = DeltaSequence.of(
                waveletProvider.getHistory(sourceWaveletName, startVersion, endVersion));
            // Construct fake index wave deltas from the deltas
            String newDigest = getWavelet(sourceWaveletName).digest;
            deltasToSend = IndexWave.createIndexDeltas(
                startVersion.getVersion(), sourceWaveletDeltas, "", newDigest);
          } catch (WaveServerException e) {
            LOG.warning("Failed to retrieve history for wavelet " + sourceWaveletName, e);
            deltasToSend = DeltaSequence.empty();
          }
          snapshotToSend = null;
        } else {
          // Send a snapshot of the current state.
          // TODO(anorth): calculate resync point if the client already knows
          // a snapshot.
          deltasToSend = DeltaSequence.empty();
          try {
            snapshotToSend = waveletProvider.getSnapshot(waveletName);
          } catch (WaveServerException e) {
            LOG.warning("Failed to retrieve snapshot for wavelet " + waveletName, e);
            openListener.onFailure("Wave server failure retrieving wavelet");
            return;
          }
        }

        LOG.info("snapshot in response is: " + (snapshotToSend != null));
        if (snapshotToSend == null) {
          // Send deltas.
          openListener.onUpdate(waveletName, snapshotToSend, deltasToSend,
              null, null, channelId);
        } else {
          // Send the snapshot.
          openListener.onUpdate(waveletName, snapshotToSend, deltasToSend,
              snapshotToSend.committedVersion, null, channelId);
        }
      }

      WaveletName dummyWaveletName = createDummyWaveletName(waveId);
      if (waveletIds.size() == 0) {
        // Send message with just the channel id.
        LOG.info("sending just a channel id for " + dummyWaveletName);
        openListener.onUpdate(dummyWaveletName, null, DeltaSequence.empty(), null, null,
            channelId);
      }
      LOG.info("sending marker for " + dummyWaveletName);
      openListener.onUpdate(dummyWaveletName, null, DeltaSequence.empty(), null, true, null);
    }
  }

  private String generateChannelID() {
    return "ch" + channel_counter.addAndGet(1);
  }

  /**
   * Initialises in-memory state for all waves, and the index wave,
   * by scanning the wavelet provider.
   *
   * The index wave is the main driver of this behaviour; when it's factored
   * out this should not be necessary.
   */
  @VisibleForTesting
  void initialiseAllWaves() throws WaveServerException {
    ExceptionalIterator<WaveId, WaveServerException> witr = waveletProvider.getWaveIds();
    Map<WaveletId, PerWavelet> indexWavelets = perWavelet.get(CommonConstants.INDEX_WAVE_ID);
    while (witr.hasNext()) {
      WaveId waveId = witr.next();
      Preconditions.checkState(!IndexWave.isIndexWave(waveId), "Index wave should not persist");
      initialiseWave(waveId);
      WaveletName indexWaveletName = IndexWave.indexWaveletNameFor(waveId);
      // IndexWavelets is a computing map, so get() initialises the entry.
      // Because the index wavelets are not persistent wavelets there's
      // no need to initialise participant or digest information.
      indexWavelets.get(indexWaveletName.waveletId);
    }
  }

  /**
   * Initialises front-end information from the wave store, if necessary.
   */
  private void initialiseWave(WaveId waveId) throws WaveServerException {
    Preconditions.checkArgument(!IndexWave.isIndexWave(waveId),
        "Late initialisation of index wave");
    if (!perWavelet.containsKey(waveId)) {
      Map<WaveletId, PerWavelet> wavelets = perWavelet.get(waveId);
      for (WaveletId waveletId : waveletProvider.getWaveletIds(waveId)) {
        ReadableWaveletData wavelet =
            waveletProvider.getSnapshot(WaveletName.of(waveId, waveletId)).snapshot;
        // Wavelets is a computing map, so get() initialises the entry.
        PerWavelet waveletInfo = wavelets.get(waveletId);
        synchronized (waveletInfo) {
          waveletInfo.currentVersion = wavelet.getHashedVersion();
          waveletInfo.digest = digest(Snippets.renderSnippet(wavelet, 80));
          waveletInfo.participants.addAll(wavelet.getParticipants());
        }
      }
    }
  }

  private boolean isWaveletWritable(WaveletName waveletName) {
    return !IndexWave.isIndexWave(waveletName.waveId);
  }

  @Override
  public void submitRequest(ParticipantId loggedInUser, final WaveletName waveletName,
      final ProtocolWaveletDelta delta, final String channelId,
      final SubmitRequestListener listener) {
    final ParticipantId author = new ParticipantId(delta.getAuthor());

    if (!author.equals(loggedInUser)) {
      listener.onFailure("Author field on delta must match logged in user");
      return;
    }

    if (!isWaveletWritable(waveletName)) {
      listener.onFailure("Wavelet " + waveletName + " is readonly");
    } else {
      perUser.get(author).submitRequest(channelId, waveletName);
      waveletProvider.submitRequest(waveletName, delta, new SubmitRequestListener() {
        @Override
        public void onSuccess(int operationsApplied,
            HashedVersion hashedVersionAfterApplication, long applicationTimestamp) {
          listener.onSuccess(operationsApplied, hashedVersionAfterApplication,
              applicationTimestamp);
          perUser.get(author).submitResponse(channelId, waveletName, hashedVersionAfterApplication);
        }

        @Override
        public void onFailure(String error) {
          listener.onFailure(error);
          perUser.get(author).submitResponse(channelId, waveletName, null);
        }
      });
    }
  }

  @Override
  public void waveletCommitted(WaveletName waveletName, HashedVersion version) {
    for (ParticipantId participant : getWavelet(waveletName).participants) {
      // TODO(arb): commits? channelId
      perUser.get(participant).onCommit(waveletName, version, null);
    }
  }

  private void participantAddedToWavelet(WaveletName waveletName, ParticipantId participant) {
    getWavelet(waveletName).participants.add(participant);
  }

  private void participantRemovedFromWavelet(WaveletName waveletName, ParticipantId participant) {
    getWavelet(waveletName).participants.remove(participant);
  }

  /**
   * Sends new deltas to a particular user on a particular wavelet, and also
   * generates fake deltas for the index wavelet. Updates the participants of
   * the specified wavelet if the participant was added or removed.
   *
   * @param waveletName which the deltas belong to
   * @param participant on the wavelet
   * @param newDeltas newly arrived deltas of relevance for participant. Must
   *        not be empty.
   * @param add whether the participant is added by the first delta
   * @param remove whether the participant is removed by the last delta
   * @param oldDigest The digest text of the wavelet before the deltas are
   *        applied (but including all changes from preceding deltas)
   * @param newDigest The digest text of the wavelet after the deltas are
   *        applied
   */
  private void participantUpdate(WaveletName waveletName, ParticipantId participant,
      DeltaSequence newDeltas, boolean add, boolean remove, String oldDigest, String newDigest) {
    if (add) {
      participantAddedToWavelet(waveletName, participant);
    }
    perUser.get(participant).onUpdate(waveletName, newDeltas);
    if (remove) {
      participantRemovedFromWavelet(waveletName, participant);
    }

    // Construct and publish fake index wave deltas
    if (IndexWave.canBeIndexed(waveletName)) {
      WaveletName indexWaveletName = IndexWave.indexWaveletNameFor(waveletName.waveId);
      long startVersion = newDeltas.getStartVersion();
      if (add) {
        participantAddedToWavelet(indexWaveletName, participant);
        startVersion = 0;
      }
      DeltaSequence indexDeltas =
          IndexWave.createIndexDeltas(startVersion, newDeltas, oldDigest, newDigest);
      if (!indexDeltas.isEmpty()) {
        perUser.get(participant).onUpdate(indexWaveletName, indexDeltas);
      }
      if (remove) {
        participantRemovedFromWavelet(indexWaveletName, participant);
      }
    }
  }

  /**
   * Based on deltas we receive from the wave server, pass the appropriate
   * membership changes and deltas from both the affected wavelets and the
   * corresponding index wave wavelets on to the UserManagers.
   */
  @Override
  public void waveletUpdate(ReadableWaveletData wavelet, DeltaSequence newDeltas) {
    if (newDeltas.isEmpty()) {
      return;
    }

    WaveletName waveletName = WaveletName.of(wavelet.getWaveId(), wavelet.getWaveletId());
    PerWavelet waveletInfo = getWavelet(waveletName);
    HashedVersion expectedVersion;
    String oldDigest;
    Set<ParticipantId> remainingParticipants;

    synchronized (waveletInfo) {
      expectedVersion = waveletInfo.getCurrentVersion();
      oldDigest = waveletInfo.digest;
      remainingParticipants = Sets.newHashSet(waveletInfo.participants);
    }

    Preconditions.checkState(expectedVersion.getVersion() == newDeltas.getStartVersion(),
        "Expected deltas starting at version %s, got %s",
        expectedVersion, newDeltas.getStartVersion());
    String newDigest = digest(Snippets.renderSnippet(wavelet, 80));

    synchronized (waveletInfo) {
      waveletInfo.setCurrentVersion(newDeltas.getEndVersion());
      waveletInfo.digest = newDigest;
    }

    // Participants added during the course of newDeltas
    Set<ParticipantId> newParticipants = Sets.newHashSet();
    for (int i = 0; i < newDeltas.size(); i++) {
      TransformedWaveletDelta delta = newDeltas.get(i);
      // Participants added or removed in this delta get the whole delta
      for (WaveletOperation op : delta) {
        if (op instanceof AddParticipant) {
          ParticipantId p = ((AddParticipant) op).getParticipantId();
          remainingParticipants.add(p);
          newParticipants.add(p);
        }
        if (op instanceof RemoveParticipant) {
          ParticipantId p = ((RemoveParticipant) op).getParticipantId();
          remainingParticipants.remove(p);
          participantUpdate(waveletName, p,
              newDeltas.subList(0, i + 1), newParticipants.remove(p), true, oldDigest, "");
        }
      }
    }

    // Send out deltas to those who end up being participants at the end
    // (either because they already were, or because they were added).
    for (ParticipantId p : remainingParticipants) {
      boolean isNew = newParticipants.contains(p);
      participantUpdate(waveletName, p, newDeltas, isNew, false, oldDigest, newDigest);
    }
  }

  private PerWavelet getWavelet(WaveletName name) {
    return perWavelet.get(name.waveId).get(name.waveletId);
  }

  private Set<WaveletId> visibleWaveletsFor(WaveViewSubscription subscription,
      ParticipantId loggedInUser) throws WaveServerException {
    Set<WaveletId> visible = Sets.newHashSet();
    Set<Entry<WaveletId, PerWavelet>> entrySet =
        perWavelet.get(subscription.getWaveId()).entrySet();
    for (Entry<WaveletId, PerWavelet> entry : entrySet) {
      WaveletName waveletName = WaveletName.of(subscription.getWaveId(), entry.getKey());
      if (subscription.includes(entry.getKey())
          && waveletProvider.checkAccessPermission(waveletName, loggedInUser)) {
        visible.add(entry.getKey());
      }
    }
    return visible;
  }

  /** Constructs a digest of the specified String. */
  private static String digest(String text) {
    int digestEndPos = text.indexOf('\n');
    if (digestEndPos < 0) {
      return text;
    } else {
      return text.substring(0, Math.min(80, digestEndPos));
    }
  }

  @VisibleForTesting
  static WaveletName createDummyWaveletName(WaveId waveId) {
    final WaveletName dummyWaveletName =
      WaveletName.of(waveId, WaveletId.of(waveId.getDomain(), "dummy+root"));
    return dummyWaveletName;
  }
}
