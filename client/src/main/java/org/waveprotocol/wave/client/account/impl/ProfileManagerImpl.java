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

package org.waveprotocol.wave.client.account.impl;

import org.waveprotocol.wave.client.account.ProfileListener;
import org.waveprotocol.wave.client.account.ProfileManager;
import org.waveprotocol.wave.model.util.CollectionUtils;
import org.waveprotocol.wave.model.util.CopyOnWriteSet;
import org.waveprotocol.wave.model.util.StringMap;
import org.waveprotocol.wave.model.wave.ParticipantId;

/**
 * A {@link ProfileManager} that returns vacuous profiles.
 *
 * @author kalman@google.com (Benjamin Kalman)
 */
public final class ProfileManagerImpl implements ProfileManager {

  private final StringMap<ProfileImpl> profiles = CollectionUtils.createStringMap();
  private final CopyOnWriteSet<ProfileListener> listeners = CopyOnWriteSet.create();
  private final String localDomain;

  public ProfileManagerImpl(String localDomain) {
    this.localDomain = localDomain;
  }

  @Override
  public ProfileImpl getProfile(ParticipantId participantId) {
    ProfileImpl profile = profiles.get(participantId.getAddress());
    if (profile == null) {
      profile = new ProfileImpl(this, participantId);
      profiles.put(participantId.getAddress(), profile);
    }

    return profile;
  }

  @Override
  public boolean shouldIgnore(ParticipantId participant) {
    return false;
  }

  @Override
  public void addListener(ProfileListener listener) {
    listeners.add(listener);
  }

  @Override
  public void removeListener(ProfileListener listener) {
    listeners.remove(listener);
  }

  void fireOnUpdated(ProfileImpl profile) {
    for (ProfileListener listener : listeners) {
      listener.onProfileUpdated(profile);
    }
  }

  @Override
  public String getLocalDomain() {
    return localDomain;
  }
}
