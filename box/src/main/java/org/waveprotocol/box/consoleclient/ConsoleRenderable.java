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
 * Objects that can be rendered for the console and support scrolling.
 *
 *
 */
public interface ConsoleRenderable {
  /**
   * Render this object given a width and height, and taking into account the scroll level.
   * If any escape codes are used then these must be unset at the end of each line and reset at
   * the start of the next.  New line and tab characters must not be rendered.
   *
   * @param width to render
   * @param height to render
   * @return exactly height lines, each of which has a length of exactly width
   */
  List<String> render(int width, int height);

  /**
   * Scroll up by a number of lines.  This will have no effect if already scrolled to the top.
   *
   * @param lines to scroll
   */
  void scrollUp(int lines);

  /**
   * Scroll down by a number of lines.  This will have no effect if already scrolled to the bottom.
   *
   * @param lines to scroll
   */
  void scrollDown(int lines);

  /**
   * Scroll to the bottom.
   */
  void scrollToBottom();

  /**
   * Scroll to the top.
   */
  void scrollToTop();

}
