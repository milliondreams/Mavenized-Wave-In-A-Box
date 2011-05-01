/**
 * Copyright 2010 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.waveprotocol.box.common;

import static org.waveprotocol.box.common.CommonConstants.INDEX_WAVE_ID;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import org.waveprotocol.wave.model.document.operation.impl.DocOpBuilder;
import org.waveprotocol.wave.model.id.IdConstants;
import org.waveprotocol.wave.model.id.IdUtil;
import org.waveprotocol.wave.model.id.InvalidIdException;
import org.waveprotocol.wave.model.id.ModernIdSerialiser;
import org.waveprotocol.wave.model.id.WaveId;
import org.waveprotocol.wave.model.id.WaveletId;
import org.waveprotocol.wave.model.id.WaveletName;
import org.waveprotocol.wave.model.operation.wave.AddParticipant;
import org.waveprotocol.wave.model.operation.wave.BlipContentOperation;
import org.waveprotocol.wave.model.operation.wave.BlipOperation;
import org.waveprotocol.wave.model.operation.wave.NoOp;
import org.waveprotocol.wave.model.operation.wave.RemoveParticipant;
import org.waveprotocol.wave.model.operation.wave.TransformedWaveletDelta;
import org.waveprotocol.wave.model.operation.wave.WaveletBlipOperation;
import org.waveprotocol.wave.model.operation.wave.WaveletOperation;
import org.waveprotocol.wave.model.operation.wave.WaveletOperationContext;
import org.waveprotocol.wave.model.version.HashedVersion;
import org.waveprotocol.wave.model.wave.ParticipantId;
import org.waveprotocol.wave.model.wave.data.ReadableWaveletData;

import java.util.List;

/**
 * Utilities for mutating the index wave, a list of waves visible to a user. The
 * index wave has a wavelet for each wave in the index. That wavelet has a
 * digest document containing a snippet of text.
 *
 * TODO(anorth): replace this with a more canonical use of the wave model.
 *
 * @author anorth@google.com (Alex North)
 * @author mk.mateng@gmail.com (Michael Kuntzman)
 */
public final class IndexWave {

  @VisibleForTesting
  public static final ParticipantId DIGEST_AUTHOR = new ParticipantId("digest-author@example.com");
  @VisibleForTesting
  public static final String DIGEST_DOCUMENT_ID = "digest";

  /**
   * @return true if the specified wave can be indexed in an index wave.
   */
  public static boolean canBeIndexed(WaveId waveId) {
    return !isIndexWave(waveId);
  }

  /**
   * @return true if the specified wavelet name can be encoded into an index
   *         wave.
   */
  public static boolean canBeIndexed(WaveletName waveletName) {
    WaveId waveId = waveletName.waveId;
    WaveletId waveletId = waveletName.waveletId;

    return canBeIndexed(waveId) && waveId.getDomain().equals(waveletId.getDomain())
        && IdUtil.isConversationRootWaveletId(waveletId);
  }

  /**
   * Constructs the deltas for an index wave wavelet in response to deltas on an
   * original conversation wavelet.
   *
   * The created delta sequence will have the same effect on the participants as
   * the original deltas and will update the digest as a function of oldDigest
   * and newDigest. The sequence may contain no-ops to pad it to the same length
   * as the source deltas.
   *
   * @param sourceDeltas conversational deltas to process
   * @param oldDigest the current index digest
   * @param newDigest the new digest
   * @return deltas to apply to the index wavelet to achieve the same change in
   *         participants, and the specified change in digest text
   */
  public static DeltaSequence createIndexDeltas(long targetVersion, DeltaSequence sourceDeltas,
      String oldDigest, String newDigest) {
    long deltaTargetVersion = targetVersion; // Target for the next delta.
    long timestamp = 0L; // No timestamp so that this is testable.
    long numSourceOps = sourceDeltas.getEndVersion().getVersion() - targetVersion;
    List<TransformedWaveletDelta> indexDeltas =
        createParticipantDeltas(sourceDeltas, deltaTargetVersion);
    long numIndexOps = numOpsInDeltas(indexDeltas);
    deltaTargetVersion += numIndexOps;

    if (numIndexOps < numSourceOps) {
      indexDeltas.add(createDigestDelta(deltaTargetVersion, timestamp, oldDigest, newDigest));
      numIndexOps += 1;
      deltaTargetVersion += 1;
    }

    if (numIndexOps < numSourceOps) {
      // Append no-ops.
      long numNoOps = numSourceOps - numIndexOps;
      List<WaveletOperation> noOps = Lists.newArrayList();
      WaveletOperationContext initialContext =
          new WaveletOperationContext(DIGEST_AUTHOR, timestamp, 1);
      for (long i = 0; i < numNoOps - 1; ++i) {
        noOps.add(new NoOp(initialContext));
      }

      HashedVersion resultingVersion = HashedVersion.unsigned(deltaTargetVersion + numNoOps);
      WaveletOperationContext finalContext =
          new WaveletOperationContext(DIGEST_AUTHOR, timestamp, 1, resultingVersion);
      noOps.add(new NoOp(finalContext));
      TransformedWaveletDelta noOpDelta =
          new TransformedWaveletDelta(DIGEST_AUTHOR, resultingVersion, 0L, noOps);
      indexDeltas.add(noOpDelta);
    }

    return DeltaSequence.of(indexDeltas);
  }

  /**
   * Retrieve a list of index entries from an index wave.
   *
   * @param wavelets wavelets to retrieve the index from.
   * @return list of index entries.
   */
  public static List<IndexEntry> getIndexEntries(Iterable<? extends ReadableWaveletData> wavelets) {
    List<IndexEntry> indexEntries = Lists.newArrayList();

    for (ReadableWaveletData wavelet : wavelets) {
      WaveId waveId = waveIdFromIndexWavelet(wavelet);
      String digest = Snippets.collateTextForWavelet(wavelet);
      indexEntries.add(new IndexEntry(waveId, digest));
    }
    return indexEntries;
  }

  /**
   * Constructs the name of the index wave wavelet that refers to the specified
   * wave.
   *
   * @param waveId referent wave id
   * @return WaveletName of the index wave wavelet referring to waveId
   * @throws IllegalArgumentException if the wave cannot be indexed
   */
  public static WaveletName indexWaveletNameFor(WaveId waveId) {
    Preconditions.checkArgument(canBeIndexed(waveId), "Wave %s cannot be indexed", waveId);
    return WaveletName.of(INDEX_WAVE_ID, WaveletId.of(waveId.getDomain(), waveId.getId()));
  }

  /**
   * @return true if the specified wave ID is an index wave ID.
   */
  public static boolean isIndexWave(WaveId waveId) {
    return waveId.equals(INDEX_WAVE_ID);
  }

  /**
   * Extracts the wave id referred to by an index wavelet's wavelet name.
   *
   * @param indexWavelet the index wavelet.
   * @return the wave id.
   * @throws IllegalArgumentException if the wavelet is not from an index wave.
   */
  public static WaveId waveIdFromIndexWavelet(ReadableWaveletData indexWavelet) {
    return waveIdFromIndexWavelet(
        WaveletName.of(indexWavelet.getWaveId(), indexWavelet.getWaveletId()));
  }

  /**
   * Extracts the wave id referred to by an index wavelet name.
   *
   * @param indexWaveletName of the index wavelet.
   * @return the wave id.
   * @throws IllegalArgumentException if the wavelet is not from an index wave.
   */
  public static WaveId waveIdFromIndexWavelet(WaveletName indexWaveletName) {
    WaveId waveId = indexWaveletName.waveId;
    Preconditions.checkArgument(isIndexWave(waveId), waveId + " is not an index wave");
    try {
      return ModernIdSerialiser.INSTANCE.deserialiseWaveId(
          ModernIdSerialiser.INSTANCE.serialiseWaveletId(indexWaveletName.waveletId));
    } catch (InvalidIdException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Extracts a wavelet name referring to the conversational root wavelet in the
   * wave referred to by an index wavelet name.
   */
  public static WaveletName waveletNameFromIndexWavelet(WaveletName indexWaveletName) {
    return WaveletName.of(IndexWave.waveIdFromIndexWavelet(indexWaveletName), WaveletId.of(
        indexWaveletName.waveletId.getDomain(), IdConstants.CONVERSATION_ROOT_WAVELET));
  }

  /**
   * Counts the ops in a sequence of deltas
   */
  private static long numOpsInDeltas(Iterable<TransformedWaveletDelta> deltas) {
    long sum = 0;
    for (TransformedWaveletDelta d : deltas) {
      sum += d.size();
    }
    return sum;
  }

  /**
   * Constructs a delta with one op which transforms the digest document from
   * one digest string to another.
   */
  private static TransformedWaveletDelta createDigestDelta(long targetVersion, long timestamp,
      String oldDigest, String newDigest) {
    HashedVersion resultingVersion = HashedVersion.unsigned(targetVersion + 1);
    WaveletOperationContext context =
      new WaveletOperationContext(DIGEST_AUTHOR, timestamp, 1, resultingVersion);
    WaveletOperation op =
        new WaveletBlipOperation(DIGEST_DOCUMENT_ID, createEditOp(oldDigest, newDigest, context));
    TransformedWaveletDelta delta = new TransformedWaveletDelta(DIGEST_AUTHOR, resultingVersion,
        timestamp, ImmutableList.of(op));
    return delta;
  }

  /** Constructs a DocOp that transforms source into target. */
  private static BlipOperation createEditOp(String source, String target,
      WaveletOperationContext context) {
    int commonPrefixLength = lengthOfCommonPrefix(source, target);
    DocOpBuilder builder = new DocOpBuilder();
    if (commonPrefixLength > 0) {
      builder.retain(commonPrefixLength);
    }
    if (source.length() > commonPrefixLength) {
      builder.deleteCharacters(source.substring(commonPrefixLength));
    }
    if (target.length() > commonPrefixLength) {
      builder.characters(target.substring(commonPrefixLength));
    }
    return new BlipContentOperation(context, builder.build());
  }

  /** Extracts participant change operations from a delta sequence. */
  private static List<TransformedWaveletDelta> createParticipantDeltas(
      Iterable<TransformedWaveletDelta> deltas, long targetVersion) {
    List<TransformedWaveletDelta> participantDeltas = Lists.newArrayList();
    for (TransformedWaveletDelta delta : deltas) {
      List<WaveletOperation> receivedParticipantOps = Lists.newArrayList();
      for (WaveletOperation op : delta) {
        if (op instanceof AddParticipant || op instanceof RemoveParticipant) {
          receivedParticipantOps.add(op);
        }
      }
      if (!receivedParticipantOps.isEmpty()) {
        HashedVersion endVersion =
            HashedVersion.unsigned(targetVersion + receivedParticipantOps.size());
        participantDeltas.add(TransformedWaveletDelta.cloneOperations(delta.getAuthor(),
            endVersion, delta.getApplicationTimestamp(), receivedParticipantOps));
        targetVersion += receivedParticipantOps.size();
      }
    }
    return participantDeltas;
  }

  /**
   * Determines the length (in number of characters) of the longest common
   * prefix of the specified two CharSequences. E.g. ("", "foo") -> 0. ("foo",
   * "bar) -> 0. ("foo", "foobar") -> 3. ("bar", "baz") -> 2.
   *
   * (Does this utility method already exist anywhere?)
   *
   * @throws NullPointerException if a or b is null
   */
  private static int lengthOfCommonPrefix(CharSequence a, CharSequence b) {
    int result = 0;
    int minLength = Math.min(a.length(), b.length());
    while (result < minLength && a.charAt(result) == b.charAt(result)) {
      result++;
    }
    return result;
  }
}
