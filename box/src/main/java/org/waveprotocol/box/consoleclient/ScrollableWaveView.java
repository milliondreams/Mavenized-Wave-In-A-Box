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

import org.waveprotocol.box.common.DocumentConstants;
import org.waveprotocol.wave.model.document.operation.AnnotationBoundaryMap;
import org.waveprotocol.wave.model.document.operation.Attributes;
import org.waveprotocol.wave.model.document.operation.DocInitializationCursor;
import org.waveprotocol.wave.model.document.operation.DocOp;
import org.waveprotocol.wave.model.document.operation.impl.InitializationCursorAdapter;
import org.waveprotocol.wave.model.wave.ParticipantId;
import org.waveprotocol.wave.model.wave.data.BlipData;
import org.waveprotocol.wave.model.wave.data.WaveletData;
import org.waveprotocol.wave.util.logging.Log;

import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * A {@link ClientWaveView} wrapper that can be rendered and scrolled for the
 * console.
 */
public class ScrollableWaveView extends ConsoleScrollable {

  private static final Log LOG = Log.get(ScrollableWaveView.class);

  public enum RenderMode {
    NORMAL,
    XML
  }

  /**
   * Wave we are wrapping.
   */
  private final ClientWaveView wave;

  /**
   * Render mode.
   */
  private RenderMode renderMode = RenderMode.NORMAL;

  /**
   * Create new scrollable wave view.
   *
   * @param wave to render
   */
  public ScrollableWaveView(ClientWaveView wave) {
    this.wave = wave;
  }

  /**
   * @return the wrapped wave view
   */
  public ClientWaveView getWave() {
    return wave;
  }

  @Override
  public synchronized List<String> render(final int width, final int height) {
    List<String> lines = Lists.newArrayList();
    WaveletData convRoot = ClientUtils.getConversationRoot(wave);

    renderManifest(convRoot, width, lines);

    // Also render a header, not too big...
    List<String> header = renderHeader(width);

    while (header.size() > height / 2) {
      header.remove(header.size() - 1);
    }

    // In this case, we actually want to scroll from the bottom.
    Collections.reverse(lines);
    List<String> reverseScroll = scroll(height - header.size(), lines);
    Collections.reverse(reverseScroll);
    ConsoleUtils.ensureHeight(width, height - header.size(), reverseScroll);

    header.addAll(reverseScroll);
    return header;
  }

  private void renderDocument(BlipData document, final int width, final List<String> lines,
      final StringBuilder currentLine, final String padding) {
    DocOp docOp = document.getContent().asOperation();
    docOp.apply(InitializationCursorAdapter.adapt(
        new DocInitializationCursor() {
          final Deque<String> elemStack = new LinkedList<String>();

          @Override
          public void characters(String s) {
            if (elemStack.getLast().equals("w:image")) {
              currentLine.append(
                  "(image, caption=" + ConsoleUtils.renderNice(s) + ")");
            } else {
              currentLine.append(padding + ConsoleUtils.renderNice(s));
            }
            wrap(lines, width, currentLine);
          }

          @Override
          public void elementStart(String type, Attributes attrs) {
            elemStack.push(type);

            if (renderMode.equals(RenderMode.NORMAL)) {
              if (type.equals(DocumentConstants.LINE)) {
                outputCurrentLine(lines, width, currentLine);
              } else if (type.equals(DocumentConstants.CONTRIBUTOR)) {
                if (attrs.containsKey(DocumentConstants.CONTRIBUTOR_NAME)) {
                  displayAuthor(attrs.get(DocumentConstants.CONTRIBUTOR_NAME));
                }
              } else if (type.equals(DocumentConstants.BODY)) {
                // Ignore.
              } else {
                LOG.warning("Unsupported element type while rendering document: " + type);
              }
            } else if (renderMode.equals(RenderMode.XML)) {
              for (int i = 0; i < elemStack.size() - 1; i++) {
                currentLine.append(" ");
              }
              if (attrs.isEmpty()) {
                currentLine.append("<" + type + ">");
              } else {
                currentLine.append("<" + type + " ");
                for (String key : attrs.keySet()) {
                  currentLine.append(key + "=\"" + attrs.get(key) + "\"");
                }
                currentLine.append(">");
              }
              outputCurrentLine(lines, width, currentLine);
            }
          }

          @Override
          public void elementEnd() {
            String type = elemStack.pop();

            if (renderMode.equals(RenderMode.XML)) {
              for (int i = 0; i < elemStack.size(); i++) {
                currentLine.append(" ");
              }
              currentLine.append("</" + type + ">");
              outputCurrentLine(lines, width, currentLine);

            }
          }

          @Override
          public void annotationBoundary(AnnotationBoundaryMap map) {
          }

          private void displayAuthor(String author) {
            ConsoleUtils.ensureWidth(width, currentLine);
            lines.add(currentLine.toString());
            currentLine.delete(0, currentLine.length() - 1);

            lines.add(ConsoleUtils.blankLine(width));
            lines.add(ConsoleUtils.ansiWrap(
                ConsoleUtils.ANSI_GREEN_FG,
                ConsoleUtils.ensureWidth(width,
                                         author)));
          }
        }));
  }

  private void renderManifest(final WaveletData waveletData, final int width,
      final List<String> lines) {
    BlipData manifest = waveletData.getDocument("conversation");
    Preconditions.checkArgument(manifest != null);

    final StringBuilder currentLine = new StringBuilder();

    if (renderMode.equals(RenderMode.XML)) {
      // Only render the manifest XML itself if we are rendering the XML.
      // TODO: refactor the XML rendering code to not share these methods (it should just iterate
      // through all documents and render the XML, ignoring the conversation model).
      renderDocument(manifest, width, lines, currentLine, "");
    }
    DocOp docOp = manifest.getContent().asOperation();
    docOp.apply(InitializationCursorAdapter.adapt(
        new DocInitializationCursor() {
          final Deque<String> elemStack = new LinkedList<String>();
          private int threadDepth;

          @Override
          public void characters(String s) {
            // Ignore characters in a manifest.
          }

          @Override
          public void elementStart(String type, Attributes attrs) {
            elemStack.push(type);

            if (renderMode.equals(RenderMode.NORMAL)) {
              if (type.equals(DocumentConstants.BLIP)) {
                if (attrs.containsKey(DocumentConstants.BLIP_ID)) {
                  BlipData document =
                      waveletData.getDocument(attrs.get(DocumentConstants.BLIP_ID));
                  if (document == null) {
                    // A nonexistent document is indistinguishable from the empty document, so this
                    // is not necessarily an error.
                  } else {
                    StringBuilder paddingBuilder = new StringBuilder();
                    for (int i = 0; i < threadDepth; i++) {
                      paddingBuilder.append("    ");
                    }
                    String padding = paddingBuilder.toString();
                    displayAuthor(padding + "Blip: " + attrs.get(DocumentConstants.BLIP_ID));
                    renderDocument(document, width, lines, currentLine, padding);
                    outputCurrentLine(lines, width, currentLine);
                  }
                }
              } else if (type.equals(DocumentConstants.THREAD)) {
                threadDepth++;
              } else if (type.equals(DocumentConstants.CONVERSATION)) {
                // There should be a toplevel conversation element in every manifest.
              } else {
                LOG.warning("Unsupported element type while rendering manifest: " + type);
              }
            } else if (renderMode.equals(RenderMode.XML)) {
              if (type.equals(DocumentConstants.BLIP)) {
                if (attrs.containsKey(DocumentConstants.BLIP_ID)) {
                  lines.add(ConsoleUtils.ansiWrap(
                      ConsoleUtils.ANSI_BLUE_FG,
                      "<!-- document named: " + attrs.get(DocumentConstants.BLIP_ID) + " -->"));
                  BlipData document = waveletData.getDocument(attrs.get(DocumentConstants.BLIP_ID));
                  if (document == null) {
                    // A nonexistent document is indistinguishable from the empty document, so this
                    // is not necessarily an error.
                  } else {
                    renderDocument(document, width, lines, currentLine, "");
                  }
                }
              } else if (type.equals(DocumentConstants.THREAD)) {
                threadDepth++;
              }
            }
          }

          @Override
          public void elementEnd() {
            String type = elemStack.pop();
            if (type.equals(DocumentConstants.THREAD)) {
              threadDepth--;
            }
          }

          @Override
          public void annotationBoundary(AnnotationBoundaryMap map) {
          }

          private void displayAuthor(String author) {
            ConsoleUtils.ensureWidth(width, currentLine);
            lines.add(currentLine.toString());
            currentLine.delete(0, currentLine.length() - 1);

            lines.add(ConsoleUtils.blankLine(width));
            lines.add(ConsoleUtils.ansiWrap(ConsoleUtils.ANSI_GREEN_FG,
                ConsoleUtils.ensureWidth(width, author)));
          }
        }));
  }

  private void outputCurrentLine(List<String> lines, int width, StringBuilder currentLine) {
    wrap(lines, width, currentLine);
    lines.add(currentLine.toString());
    currentLine.delete(0, currentLine.length());
  }

  /**
   * Render a header, containing extra information about the participants.
   *
   * @param width of the header
   * @return list of lines that make up the header
   */
  private List<String> renderHeader(int width) {
    List<String> lines = Lists.newArrayList();
    Set<ParticipantId> participants = ClientUtils.getConversationRoot(wave).getParticipants();

    // HashedVersion
    StringBuilder versionLineBuilder = new StringBuilder();
    versionLineBuilder.append("Version "
        + wave.getWaveletVersion(ClientUtils.getConversationRootId(wave)));
    wrapAndClose(lines, width, versionLineBuilder);

    // Participants
    StringBuilder participantLineBuilder = new StringBuilder();

    if (participants.isEmpty()) {
      participantLineBuilder.append("No participants!?");
    } else {
      Iterator<ParticipantId> it = participants.iterator();
      participantLineBuilder.append("With ");
      participantLineBuilder.append(it.next());

      while(it.hasNext()){
        participantLineBuilder.append(", ");
        participantLineBuilder.append(it.next());
      }
    }

    // Render as lines.
    wrapAndClose(lines, width, participantLineBuilder);

    for (int i = 0; i < lines.size(); i++) {
      lines.set(i, ConsoleUtils.ansiWrap(ConsoleUtils.ANSI_YELLOW_FG, lines.get(i)));
    }

    lines.add(ConsoleUtils.ensureWidth(width, "----"));
    return lines;
  }

  /**
   * Wrap a line by continually removing characters from a string and adding to
   * a list of lines, until the line is shorter than width.
   *
   * @param lines to append the wrapped string to
   * @param width to wrap
   * @param line  to wrap
   */
  private void wrap(List<String> lines, int width, StringBuilder line) {
    while (line.length() >= width) {
      lines.add(line.substring(0, width));
      line.delete(0, width);
    }
  }

  /**
   * Wrap a line as in {@code wrap}, then "close" it by adding any remaining
   * characters to the list of lines and clearing the line.
   *
   * @param lines to append line to
   * @param width to wrap
   * @param line  to append
   */
  private void wrapAndClose(List<String> lines, int width, StringBuilder line) {
    wrap(lines, width, line);

    if (line.length() > 0) {
      lines.add(ConsoleUtils.ensureWidth(width, line.toString()));
      line.delete(0, line.length());
    }
  }

  /**
   * @return the current rendering mode
   */
  public RenderMode getRenderingMode() {
    return renderMode;
  }

  /**
   * Set rendering mode.
   *
   * @param mode for rendering
   */
  public void setRenderingMode(RenderMode mode) {
    this.renderMode = mode;
  }
}
