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

package org.waveprotocol.wave.client.doodad;

import org.waveprotocol.wave.client.editor.content.Registries;
import org.waveprotocol.wave.model.conversation.Conversation;
import org.waveprotocol.wave.model.conversation.ConversationBlip;
import org.waveprotocol.wave.model.wave.Wavelet;

/**
 * Defines the type namespace for doodad installers.
 *
 */
public final class DoodadInstallers {

  //
  // This class is merely used as a namespace; no instances are intended.
  //

  private DoodadInstallers() {
  }

  /**
   * Installer for a context-free doodad handler.
   */
  public interface GlobalInstaller {
    void install(Registries r);
  }

  /**
   * Installer for a per-conversation doodad handler.
   */
  public interface ConversationInstaller {
    void install(Wavelet w, Conversation c, Registries r);
  }

  /**
   * Installer for a per-blip doodad handler.
   */
  public interface BlipInstaller {
    void install(Wavelet w, Conversation c, ConversationBlip b, Registries r);
  }
}
