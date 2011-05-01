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

package org.waveprotocol.box.consoleclient;

import org.waveprotocol.wave.model.operation.wave.BlipOperation;
import org.waveprotocol.wave.model.version.HashedVersion;
import org.waveprotocol.wave.model.wave.ParticipantId;
import org.waveprotocol.wave.model.wave.data.WaveletData;

/**
 * Notification interface for wavelet operations, with an additional two methods defined with
 * efficient rendering in mind.  {@code waveletDocumentUpdated}, {@code participantAdded}, and
 * {@code participantRemoved} are each called <em>after</em> the operation has been applied.
 *
 *
 */
public interface WaveletOperationListener {
  /**
   * Invoked when an operation is applied to a document of a wavelet.
   *
   * @param author the author of the event
   * @param wavelet the wavelet operated on
   * @param docId the id of the document the operation is performed on
   * @param docOp operation performed on the document
   */
  public void waveletDocumentUpdated(String author, WaveletData wavelet,
      String docId, BlipOperation docOp);

  /**
   * Invoked when a participant has been added to a wavelet.
   *
   * @param author the author of the event
   * @param wavelet the wavelet operated on
   * @param participantId the id of the participant added
   */
  public void participantAdded(String author, WaveletData wavelet, ParticipantId participantId);

  /**
   * Invoked when a participant has been removed from a wavelet.
   *
   * @param author the author of the event
   * @param wavelet the wavelet operated on
   * @param participantId the id of the participant removed
   */
  public void participantRemoved(String author, WaveletData wavelet, ParticipantId participantId);

  /**
   * Invoked on a wavelet NoOp.
   *
   * @param author the author of the event
   * @param wavelet the wavelet (not) operated on
   */
  public void noOp(String author, WaveletData wavelet);

  /**
   * Invoked before a sequence of deltas is applied.
   * TODO: remove this.
   */
  public void onDeltaSequenceStart(WaveletData wavelet);

  /**
   * Invoked after a sequence of deltas has been applied.  It will probably be most appropriate to
   * do any rendering for wavelet changes in this method.
   * TODO: remove this, replace with a better rendering optimisation.
   */
  public void onDeltaSequenceEnd(WaveletData wavelet);

  /**
   * Invoked when a commit notice is received.
   *
   * @param wavelet the wavelet that was committed
   * @param version the latest version the server has committed to disk.
   */
  public void onCommitNotice(WaveletData wavelet, HashedVersion version);
}
