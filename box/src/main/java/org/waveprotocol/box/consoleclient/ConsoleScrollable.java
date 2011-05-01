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

import java.util.List;

/**
 * Abstract implementation of {@link ConsoleRenderable} that supports scrolling.
 *
 *
 */
public abstract class ConsoleScrollable implements ConsoleRenderable {

  /** Amount scrolled, starting from 0. */
  private int scrollLevel = 0;

  /**
   * Scroll a list of lines based on the value of scrollLevel.
   *
   * @param height of the scroll window
   * @param lines to scroll
   * @return list of at most height lines, scrolled based on the value of scrollLevel
   */
  public synchronized List<String> scroll(int height, List<String> lines) {
    if (scrollLevel > lines.size() - height) {
      scrollLevel = Math.max(0, lines.size() - height);
    }

    return lines.subList(scrollLevel, Math.min(lines.size(), scrollLevel + height));
  }

  @Override
  public synchronized void scrollDown(int lines) {
    if (scrollLevel < Integer.MAX_VALUE - lines) {
      scrollLevel += lines;
    } else {
      scrollLevel = Integer.MAX_VALUE;
    }
  }

  @Override
  public synchronized void scrollToTop() {
    scrollLevel = 0;
  }

  @Override
  public synchronized void scrollToBottom() {
    // Hack, but easier that the alternative (rendering whole wave to find end, or setting a flag)
    // This will be set to a sane height we re-rendered so it should be safe, unless scrolling up
    // in between... but that isn't happening at the moment
    scrollLevel = Integer.MAX_VALUE;
  }

  @Override
  public synchronized void scrollUp(int lines) {
    scrollLevel = Math.max(0, scrollLevel - lines);
  }
}
