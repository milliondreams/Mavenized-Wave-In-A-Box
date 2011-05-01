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

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import org.waveprotocol.box.common.IndexEntry;
import org.waveprotocol.box.common.IndexWave;
import org.waveprotocol.wave.model.version.HashedVersion;

import java.util.List;
import java.util.Map;

/**
 * Wraps and renders the index wave.
 *
 *
 */
public class ScrollableInbox extends ConsoleScrollable {
  /** Index wave we are rendering. */
  private final ClientWaveView indexWave;

  /** Backend the index wave belongs to. */
  private final ClientBackend backend;

  /** Open wave, to potentially render differently. */
  private ClientWaveView openWave = null;

  /** Last known versions of each wave. */
  private final Map<ClientWaveView, HashedVersion> lastSeenVersions = Maps.newHashMap();

  /**
   * Create new scrollable inbox given an index wave.
   *
   * @param backend the index wave belongs to
   * @param indexWave to render
   */
  public ScrollableInbox(ClientBackend backend, ClientWaveView indexWave) {
    Preconditions.checkArgument(IndexWave.isIndexWave(indexWave.getWaveId()), indexWave +
        " is not an index wave");

    this.indexWave = indexWave;
    this.backend = backend;
  }

  /**
   * Check if a particular wave has been read.
   *
   * @param wave to check
   * @return true if all latest changes to the wave have bee seen.
   */
  public boolean isRead(ClientWaveView wave) {
    if ((wave != null) && (ClientUtils.getConversationRoot(wave) != null)) {
      HashedVersion version = wave.getWaveletVersion(ClientUtils.getConversationRootId(wave));
      return version.equals(lastSeenVersions.get(wave));
    } else {
      return false;
    }
  }

  /**
   * Mark a particular wave as read.
   *
   * @param wave to mark
   */
  // TODO: use a wavelet to store unread status.
  public void markAsRead(ClientWaveView wave) {
    if ((wave != null) && (ClientUtils.getConversationRoot(wave) != null)) {
      HashedVersion version = wave.getWaveletVersion(ClientUtils.getConversationRootId(wave));
      lastSeenVersions.put(wave, version);
    }
  }

  /**
   * Mark all waves as read.
   */
  public void markAllAsRead() {
    for (IndexEntry indexEntry : IndexWave.getIndexEntries(indexWave.getWavelets())) {
      ClientWaveView wave = backend.getWave(indexEntry.getWaveId());
      markAsRead(wave);
    }
  }

  @Override
  public List<String> render(int width, int height) {
    List<IndexEntry> indexEntries = IndexWave.getIndexEntries(indexWave.getWavelets());
    List<String> lines = Lists.newArrayList();

    for (int i = 0; i < indexEntries.size(); i++) {
      ClientWaveView wave = backend.getWave(indexEntries.get(i).getWaveId());
      StringBuilder line = new StringBuilder();
      List<Integer> ansiCodes = Lists.newArrayList();

      if ((wave == null) || (ClientUtils.getConversationRoot(wave) == null)) {
        line.append("...");
      } else {
        line.append(String.format("%4d) ", i));
        line.append(String.format("(%s) ", wave.getWaveId().getId()));
        line.append(ConsoleUtils.renderNice(indexEntries.get(i).getDigest()));

        if (wave == openWave) {
          ansiCodes.add(ConsoleUtils.ANSI_BLUE_BG);
          ansiCodes.add(ConsoleUtils.ANSI_WHITE_FG);
          markAsRead(wave);
        } else if (!isRead(wave)) {
          ansiCodes.add(ConsoleUtils.ANSI_BOLD);
        }
      }

      ConsoleUtils.ensureWidth(width, line);
      lines.add(ConsoleUtils.ansiWrap(ansiCodes, line.toString()));
    }

    List<String> scrolledLines = scroll(height, lines);
    ConsoleUtils.ensureHeight(width, height, scrolledLines);
    return scrolledLines;
  }

  /**
   * Set the open wave.
   *
   * @param openWave the new open wave
   */
  public void setOpenWave(ClientWaveView openWave) {
    this.openWave = openWave;
  }
}
