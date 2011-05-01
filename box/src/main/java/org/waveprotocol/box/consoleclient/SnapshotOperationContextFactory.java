/**
 * Copyright 2010 Google Inc.
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

import org.waveprotocol.wave.model.operation.wave.AbstractWaveletOperationContextFactory;
import org.waveprotocol.wave.model.wave.ParticipantId;
import org.waveprotocol.wave.model.wave.data.ReadableWaveletData;

/**
 * A context factory which uses a wavelet's creator and last modified time as
 * context.
 *
 * This class produces contexts which do not reflect the true origin of
 * operations.
 *
 * TODO(anorth): Remove this class when {@link ClientBackend}
 * doesn't generate client events from snapshots.
 *
 * @author anorth@google.com (Alex North)
 */
public final class SnapshotOperationContextFactory extends AbstractWaveletOperationContextFactory {
  private final ReadableWaveletData wavelet;

  public SnapshotOperationContextFactory(ReadableWaveletData wavelet) {
    this.wavelet = wavelet;
  }

  @Override
  protected long currentTimeMillis() {
    return wavelet.getLastModifiedTime();
  }

  @Override
  protected ParticipantId getParticipantId() {
    return wavelet.getCreator();
  }
}
