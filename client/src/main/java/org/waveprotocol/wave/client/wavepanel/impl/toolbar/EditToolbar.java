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

package org.waveprotocol.wave.client.wavepanel.impl.toolbar;

import com.google.common.base.Preconditions;
import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.Document;
import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.Style.Unit;
import com.google.gwt.user.client.Window;

import org.waveprotocol.wave.client.common.util.WaveRefConstants;
import org.waveprotocol.wave.client.doodad.link.Link;
import org.waveprotocol.wave.client.doodad.link.Link.InvalidLinkException;
import org.waveprotocol.wave.client.editor.Editor;
import org.waveprotocol.wave.client.editor.EditorContext;
import org.waveprotocol.wave.client.editor.EditorUpdateEvent;
import org.waveprotocol.wave.client.editor.EditorUpdateEvent.EditorUpdateListener;
import org.waveprotocol.wave.client.editor.content.ContentElement;
import org.waveprotocol.wave.client.editor.content.ContentNode;
import org.waveprotocol.wave.client.editor.content.misc.StyleAnnotationHandler;
import org.waveprotocol.wave.client.editor.content.paragraph.Paragraph;
import org.waveprotocol.wave.client.editor.content.paragraph.Paragraph.LineStyle;
import org.waveprotocol.wave.client.editor.util.EditorAnnotationUtil;
import org.waveprotocol.wave.client.gadget.GadgetXmlUtil;
import org.waveprotocol.wave.client.scheduler.BucketRateLimiter;
import org.waveprotocol.wave.client.scheduler.CancellableCommand;
import org.waveprotocol.wave.client.scheduler.SchedulerInstance;
import org.waveprotocol.wave.client.scheduler.TimerService;
import org.waveprotocol.wave.client.widget.toolbar.SubmenuToolbarView;
import org.waveprotocol.wave.client.widget.toolbar.ToolbarButtonViewBuilder;
import org.waveprotocol.wave.client.widget.toolbar.ToolbarView;
import org.waveprotocol.wave.client.widget.toolbar.ToplevelToolbarWidget;
import org.waveprotocol.wave.client.widget.toolbar.buttons.ToolbarClickButton;
import org.waveprotocol.wave.client.widget.toolbar.buttons.ToolbarToggleButton;
import org.waveprotocol.wave.model.document.indexed.LocationMapper;
import org.waveprotocol.wave.model.document.util.FocusedRange;
import org.waveprotocol.wave.model.document.util.LineContainers;
import org.waveprotocol.wave.model.document.util.Range;
import org.waveprotocol.wave.model.document.util.XmlStringBuilder;
import org.waveprotocol.wave.model.util.CopyOnWriteSet;
import org.waveprotocol.wave.model.wave.ParticipantId;

import java.util.Iterator;

/**
 * Attaches editors to a toolbar.
 *
 * @author kalman@google.com (Benjamin Kalman)
 */
public class EditToolbar implements EditorUpdateListener {

  /**
   * Handler for click buttons added with {@link EditToolbar#addClickButton}.
   */
  public interface ClickHandler {
    void onClicked(EditorContext context);
  }

  /** The default gadget URL used in the insert-gadget prompt */
  private static final String YES_NO_MAYBE_GADGET =
      "http://wave-api.appspot.com/public/gadgets/areyouin/gadget.xml";

  /** Something that needs updating. */
  interface Controller {
    void update(Range selectionRange);
  }

  /**
   * A {@link ToolbarToggleButton.Listener} which applies a styling to a
   * selection of text.
   */
  private final class TextSelectionController implements ToolbarToggleButton.Listener, Controller {
    public final ToolbarToggleButton button;
    public final String styleKey;
    public final String styleValue;

    public TextSelectionController(ToolbarToggleButton button, String styleKey, String styleValue) {
      this.button = button;
      this.styleKey = StyleAnnotationHandler.key(styleKey);
      this.styleValue = styleValue;
    }

    @Override
    public void onToggledOff() {
      setSelectionStyle(null);
    }

    @Override
    public void onToggledOn() {
      setSelectionStyle(styleValue);
    }

    private void setSelectionStyle(final String style) {
      if (editor.getSelectionHelper().getSelectionPoints() != null) {
        editor.undoableSequence(new Runnable() {
          @Override public void run() {
            EditorAnnotationUtil.setAnnotationOverSelection(
                editor, styleKey, style);
          }
        });
      }
    }

    @Override
    public void update(Range selectionRange) {
      if (styleValue != null) {
        String value =
            EditorAnnotationUtil.getAnnotationOverRangeIfFull(editor.getDocument(),
                editor.getCaretAnnotations(), styleKey, selectionRange.getStart(),
                selectionRange.getEnd());
        button.setToggledOn(styleValue.equals(value));
      }
    }
  }

  /**
   * A {@link ToolbarClickButton.Listener} which applies a styling to a
   * paragraph using {@link Paragraph#traverse}.
   */
  private final class ParagraphTraversalController implements ToolbarClickButton.Listener {
    private final ContentElement.Action action;

    public ParagraphTraversalController(ContentElement.Action action) {
      this.action = action;
    }

    @Override
    public void onClicked() {
      final Range range = editor.getSelectionHelper().getOrderedSelectionRange();
      if (range != null) {
        editor.undoableSequence(new Runnable(){
          @Override public void run() {
            LocationMapper<ContentNode> locator = editor.getDocument();
            Paragraph.traverse(locator, range.getStart(), range.getEnd(), action);
          }
        });
      }
    }
  }

  /**
   * A {@link ToolbarToggleButton.Listener} which applies a styling to a
   * paragraph using {@link Paragraph#apply}.
   */
  private final class ParagraphApplicationController
      implements ToolbarToggleButton.Listener, Controller {
    public final ToolbarToggleButton button;
    public final Paragraph.LineStyle style;

    public ParagraphApplicationController(ToolbarToggleButton button, Paragraph.LineStyle style) {
      this.button = button;
      this.style = style;
    }

    @Override
    public void onToggledOn() {
      setParagraphStyle(true);
    }

    @Override
    public void onToggledOff() {
      setParagraphStyle(false);
    }

    private void setParagraphStyle(final boolean isOn) {
      final Range range = editor.getSelectionHelper().getOrderedSelectionRange();
      if (range != null) {
        editor.undoableSequence(new Runnable(){
          @Override public void run() {
            Paragraph.apply(editor.getDocument(), range.getStart(), range.getEnd(), style, isOn);
          }
        });
      }
    }

    @Override
    public void update(Range range) {
      button.setToggledOn(
          Paragraph.appliesEntirely(editor.getDocument(), range.getStart(), range.getEnd(), style));
    }
  }

  /**
   * Container for a font family.
   */
  private static final class FontFamily {
    public final String description;
    public final String style;
    public FontFamily(String description, String style) {
      this.description = description;
      this.style = style;
    }
  }

  /**
   * Container for an alignment.
   */
  private static final class Alignment {
    public final String description;
    public final String iconCss;
    public final LineStyle style;
    public Alignment(String description, String iconCss, LineStyle style) {
      this.description = description;
      this.iconCss = iconCss;
      this.style = style;
    }
  }

  /**
   * Updates buttons asynchronously as a rate-limited background task. Buttons
   * are not updated synchronously on editor events, because button updates
   * typically involve annotation queries, which are quite slow.
   */
  private final static class ButtonUpdater implements CancellableCommand {
    // In non-compiled mode, a single button update is on the order of 10ms. In
    // compiled mode, it is more in the order of 1ms.
    // With ~40 buttons to update, there is significant lag in non-compiled
    // mode. This is addressed by rate-limiting the updates, with a very large
    // delay in compiled mode. In non-compiled mode, frequent lag of ~50ms is
    // noticeable, and so we still rate limit the updates, but with a more
    // generous speed.
    private final BucketRateLimiter runner;
    // CopyOnWriteSet is used to ensure that the collection does not change
    // while iterating.
    private final CopyOnWriteSet<Controller> updateables = CopyOnWriteSet.create();

    ButtonUpdater(TimerService scheduler) {
      this.runner = new BucketRateLimiter(scheduler,
          // Never queue more than one update at a time.
          1,
          // Never fire more than one update at a time.
          1,
          // Update at most 3 times per second in compiled mode, but much less
          // frequently in non-compiled mode.
          GWT.isScript() ? 333 : 2000);
    }

    /** Controllers to update. */
    private Iterator<Controller> iterator;

    /** Range to use on updates. */
    private Range range;

    /** Whether this updater should do any work. True while in an edit session. */
    private boolean enabled;

    void enable() {
      enabled = true;
    }

    void disable() {
      enabled = false;
      runner.cancelAll();
    }

    /** Adds a controller to the update list. */
    void add(Controller controller) {
      updateables.add(controller);
    }

    void invalidate(Range selectionRange) {
      if (!enabled || updateables.isEmpty()) {
        return;
      }
      iterator = updateables.iterator();
      range = selectionRange;
      if (range != null && iterator.hasNext()) {
        runner.schedule(this);
      } else {
        runner.cancel(this);
      }
    }

    @Override
    public void onCancelled() {
      // Ignore.
    }

    @Override
    public void execute() {
      assert enabled;
      for (Controller update : updateables) {
        update.update(range);
      }
    }
  }

  private final EditorToolbarResources.Css css;
  private final ToplevelToolbarWidget toolbarUi;
  private final ParticipantId user;

  private final ButtonUpdater updater;
  private EditorContext editor;

  private EditToolbar(EditorToolbarResources.Css css, ToplevelToolbarWidget toolbarUi,
      ButtonUpdater updater, ParticipantId user) {
    this.css = css;
    this.toolbarUi = toolbarUi;
    this.updater = updater;
    this.user = user;
  }

  /**
   * Attaches editor behaviour to a toolbar, adding all the edit buttons.
   */
  public static EditToolbar create(ParticipantId user) {
    ToplevelToolbarWidget toolbarUi = new ToplevelToolbarWidget();
    EditorToolbarResources.Css css = EditorToolbarResources.Loader.res.css();
    TimerService timer = SchedulerInstance.getMediumPriorityTimer();
    ButtonUpdater updater = new ButtonUpdater(timer);
    return new EditToolbar(css, toolbarUi, updater, user);
  }

  public void init() {
    ToolbarView group = toolbarUi.addGroup();
    createBoldButton(group);
    createItalicButton(group);
    createUnderlineButton(group);
    createStrikethroughButton(group);

    group = toolbarUi.addGroup();
    createSuperscriptButton(group);
    createSubscriptButton(group);

    group = toolbarUi.addGroup();
    createFontSizeButton(group);
    createFontFamilyButton(group);
    createHeadingButton(group);

    group = toolbarUi.addGroup();
    createIndentButton(group);
    createOutdentButton(group);

    group = toolbarUi.addGroup();
    createUnorderedListButton(group);
    createOrderedListButton(group);

    group = toolbarUi.addGroup();
    createAlignButtons(group);
    createClearFormattingButton(group);

    group = toolbarUi.addGroup();
    createInsertLinkButton(group);
    createRemoveLinkButton(group);

    group = toolbarUi.addGroup();
    createInsertGadgetButton(group, user);
  }

  private void createBoldButton(ToolbarView toolbar) {
    ToolbarToggleButton b = toolbar.addToggleButton();
    new ToolbarButtonViewBuilder()
        .setIcon(css.bold())
        .applyTo(b, createTextSelectionController(b, "fontWeight", "bold"));
  }

  private void createItalicButton(ToolbarView toolbar) {
    ToolbarToggleButton b = toolbar.addToggleButton();
    new ToolbarButtonViewBuilder()
        .setIcon(css.italic())
        .applyTo(b, createTextSelectionController(b, "fontStyle", "italic"));
  }

  private void createUnderlineButton(ToolbarView toolbar) {
    ToolbarToggleButton b = toolbar.addToggleButton();
    new ToolbarButtonViewBuilder()
        .setIcon(css.underline())
        .applyTo(b, createTextSelectionController(b, "textDecoration", "underline"));
  }

  private void createStrikethroughButton(ToolbarView toolbar) {
    ToolbarToggleButton b = toolbar.addToggleButton();
    new ToolbarButtonViewBuilder()
        .setIcon(css.strikethrough())
        .applyTo(b, createTextSelectionController(b, "textDecoration", "line-through"));
  }

  private void createSuperscriptButton(ToolbarView toolbar) {
    ToolbarToggleButton b = toolbar.addToggleButton();
    new ToolbarButtonViewBuilder()
        .setIcon(css.superscript())
        .applyTo(b, createTextSelectionController(b, "verticalAlign", "super"));
  }

  private void createSubscriptButton(ToolbarView toolbar) {
    ToolbarToggleButton b = toolbar.addToggleButton();
    new ToolbarButtonViewBuilder()
        .setIcon(css.subscript())
        .applyTo(b, createTextSelectionController(b, "verticalAlign", "sub"));
  }

  private void createFontSizeButton(ToolbarView toolbar) {
    SubmenuToolbarView submenu = toolbar.addSubmenu();
    new ToolbarButtonViewBuilder()
        .setIcon(css.fontSize())
        .applyTo(submenu, null);
    submenu.setShowDropdownArrow(false); // Icon already has dropdown arrow.
    // TODO(kalman): default text size option.
    ToolbarView group = submenu.addGroup();
    for (int size : asArray(8, 9, 10, 11, 12, 14, 16, 18, 21, 24, 28, 32, 36, 42, 48, 56, 64, 72)) {
      ToolbarToggleButton b = group.addToggleButton();
      double baseSize = 12.0;
      b.setVisualElement(createFontSizeElement(baseSize, size));
      b.setListener(createTextSelectionController(b, "fontSize", (size / baseSize) + "em"));
    }
  }

  private Element createFontSizeElement(double baseSize, double size) {
    Element e = Document.get().createSpanElement();
    e.getStyle().setFontSize(size / baseSize, Unit.EM);
    e.setInnerText(((int) size) + "");
    return e;
  }

  private void createFontFamilyButton(ToolbarView toolbar) {
    SubmenuToolbarView submenu = toolbar.addSubmenu();
    new ToolbarButtonViewBuilder()
        .setIcon(css.fontFamily())
        .applyTo(submenu, null);
    submenu.setShowDropdownArrow(false); // Icon already has dropdown arrow.
    createFontFamilyGroup(submenu.addGroup(), new FontFamily("Default", null));
    createFontFamilyGroup(submenu.addGroup(),
        new FontFamily("Sans Serif", "sans-serif"),
        new FontFamily("Serif", "serif"),
        new FontFamily("Wide", "arial black,sans-serif"),
        new FontFamily("Narrow", "arial narrow,sans-serif"),
        new FontFamily("Fixed Width", "monospace"));
    createFontFamilyGroup(submenu.addGroup(),
        new FontFamily("Arial", "arial,helvetica,sans-serif"),
        new FontFamily("Comic Sans MS", "comic sans ms,sans-serif"),
        new FontFamily("Courier New", "courier new,monospace"),
        new FontFamily("Garamond", "garamond,serif"),
        new FontFamily("Georgia", "georgia,serif"),
        new FontFamily("Tahoma", "tahoma,sans-serif"),
        new FontFamily("Times New Roman", "times new roman,serif"),
        new FontFamily("Trebuchet MS", "trebuchet ms,sans-serif"),
        new FontFamily("Verdana", "verdana,sans-serif"));
  }

  private void createFontFamilyGroup(ToolbarView toolbar, FontFamily... families) {
    for (FontFamily family : families) {
      ToolbarToggleButton b = toolbar.addToggleButton();
      b.setVisualElement(createFontFamilyElement(family));
      b.setListener(createTextSelectionController(b, "fontFamily", family.style));
    }
  }

  private Element createFontFamilyElement(FontFamily family) {
    Element e = Document.get().createSpanElement();
    e.getStyle().setProperty("fontFamily", family.style);
    e.setInnerText(family.description);
    return e;
  }

  private void createClearFormattingButton(ToolbarView toolbar) {
    new ToolbarButtonViewBuilder()
        .setIcon(css.clearFormatting())
        .applyTo(toolbar.addClickButton(), new ToolbarClickButton.Listener() {
          @Override public void onClicked() {
            EditorAnnotationUtil.clearAnnotationsOverSelection(editor, asArray(
                StyleAnnotationHandler.key("backgroundColor"),
                StyleAnnotationHandler.key("color"),
                StyleAnnotationHandler.key("fontFamily"),
                StyleAnnotationHandler.key("fontSize"),
                StyleAnnotationHandler.key("fontStyle"),
                StyleAnnotationHandler.key("fontWeight"),
                StyleAnnotationHandler.key("textDecoration")
                // NOTE: add more as required.
            ));
            createClearHeadingsListener().onClicked();
          }
        });
  }

  private void createInsertGadgetButton(ToolbarView toolbar, final ParticipantId user) {
    new ToolbarButtonViewBuilder()
        .setIcon(css.insertGadget())
        .applyTo(toolbar.addClickButton(), new ToolbarClickButton.Listener() {
          @Override public void onClicked() {
            String url = Window.prompt("Gadget URL", YES_NO_MAYBE_GADGET);
            if (url != null && !url.isEmpty()) {
              XmlStringBuilder xml = GadgetXmlUtil.constructXml(url, "", user.getAddress());
              LineContainers.appendLine(editor.getDocument(), xml);
            }
          }
        });
  }

  private void createInsertLinkButton(ToolbarView toolbar) {
    // TODO (Yuri Z.) use createTextSelectionController when the full link doodad
    // is incorporated
    new ToolbarButtonViewBuilder()
        .setIcon(css.insertLink())
        .applyTo(toolbar.addClickButton(), new ToolbarClickButton.Listener() {
              @Override  public void onClicked() {
                FocusedRange range = editor.getSelectionHelper().getSelectionRange();
                if (range == null || range.isCollapsed()) {
                  Window.alert("Select some text to create a link.");
                  return;
                }
                String rawLinkValue =
                    Window.prompt("Enter link: URL or Wave ID.", WaveRefConstants.WAVE_URI_PREFIX);
                // user hit "ESC" or "cancel"
                if (rawLinkValue == null) {
                  return;
                }
                try {
                  String linkAnnotationValue = Link.normalizeLink(rawLinkValue);
                  EditorAnnotationUtil.setAnnotationOverSelection(editor, Link.MANUAL_KEY,
                      linkAnnotationValue);
                } catch (InvalidLinkException e) {
                  Window.alert(e.getLocalizedMessage());
                }
              }
            });
  }

  private void createRemoveLinkButton(ToolbarView toolbar) {
    new ToolbarButtonViewBuilder()
        .setIcon(css.removeLink())
        .applyTo(toolbar.addClickButton(), new ToolbarClickButton.Listener() {
          @Override public void onClicked() {
            if (editor.getSelectionHelper().getSelectionRange() != null) {
              EditorAnnotationUtil.clearAnnotationsOverSelection(editor, Link.LINK_KEYS);
            }
          }
        });
  }

  private ToolbarClickButton.Listener createClearHeadingsListener() {
    return new ParagraphTraversalController(new ContentElement.Action() {
        @Override public void execute(ContentElement e) {
          e.getMutableDoc().setElementAttribute(e, Paragraph.SUBTYPE_ATTR, null);
        }
      });
  }

  private void createHeadingButton(ToolbarView toolbar) {
    SubmenuToolbarView submenu = toolbar.addSubmenu();
    new ToolbarButtonViewBuilder()
        .setIcon(css.heading())
        .applyTo(submenu, null);
    submenu.setShowDropdownArrow(false); // Icon already has dropdown arrow.
    ToolbarClickButton defaultButton = submenu.addClickButton();
    new ToolbarButtonViewBuilder()
        .setText("Default")
        .applyTo(defaultButton, createClearHeadingsListener());
    ToolbarView group = submenu.addGroup();
    for (int level : asArray(1, 2, 3, 4)) {
      ToolbarToggleButton b = group.addToggleButton();
      b.setVisualElement(createHeadingElement(level));
      b.setListener(createParagraphApplicationController(b, Paragraph.regularStyle("h" + level)));
    }
  }

  private Element createHeadingElement(int level) {
    Element e = Document.get().createElement("h" + level);
    e.getStyle().setMarginTop(2, Unit.PX);
    e.getStyle().setMarginBottom(2, Unit.PX);
    e.setInnerText("Heading " + level);
    return e;
  }

  private void createIndentButton(ToolbarView toolbar) {
    ToolbarClickButton b = toolbar.addClickButton();
    new ToolbarButtonViewBuilder()
        .setIcon(css.indent())
        .applyTo(b, new ParagraphTraversalController(Paragraph.INDENTER));
  }

  private void createOutdentButton(ToolbarView toolbar) {
    ToolbarClickButton b = toolbar.addClickButton();
    new ToolbarButtonViewBuilder()
        .setIcon(css.outdent())
        .applyTo(b, new ParagraphTraversalController(Paragraph.OUTDENTER));
  }

  private void createUnorderedListButton(ToolbarView toolbar) {
    ToolbarToggleButton b = toolbar.addToggleButton();
    new ToolbarButtonViewBuilder()
        .setIcon(css.unorderedlist())
        .applyTo(b, createParagraphApplicationController(b, Paragraph.listStyle(null)));
  }

  private void createOrderedListButton(ToolbarView toolbar) {
    ToolbarToggleButton b = toolbar.addToggleButton();
    new ToolbarButtonViewBuilder()
        .setIcon(css.orderedlist())
        .applyTo(b, createParagraphApplicationController(
            b, Paragraph.listStyle(Paragraph.LIST_STYLE_DECIMAL)));
  }

  private void createAlignButtons(ToolbarView toolbar) {
    SubmenuToolbarView submenu = toolbar.addSubmenu();
    new ToolbarButtonViewBuilder()
        .setIcon(css.alignDrop())
        .applyTo(submenu, null);
    submenu.setShowDropdownArrow(false); // Icon already has dropdown arrow.
    ToolbarView group = submenu.addGroup();
    for (Alignment alignment : asArray(
        new Alignment("Left", css.alignLeft(), Paragraph.Alignment.LEFT),
        new Alignment("Centre", css.alignCentre(), Paragraph.Alignment.CENTER),
        new Alignment("Right", css.alignRight(), Paragraph.Alignment.RIGHT))) {
      ToolbarToggleButton b = group.addToggleButton();
      new ToolbarButtonViewBuilder()
          .setText(alignment.description)
          .setIcon(alignment.iconCss)
          .applyTo(b, createParagraphApplicationController(b, alignment.style));
    }
  }

  private TextSelectionController createTextSelectionController(
      ToolbarToggleButton button, String styleName, String styleValue) {
    TextSelectionController controller = new TextSelectionController(button, styleName, styleValue);
    updater.add(controller);
    return controller;
  }

  private ParagraphApplicationController createParagraphApplicationController(
      ToolbarToggleButton button, LineStyle style) {
    ParagraphApplicationController controller = new ParagraphApplicationController(button, style);
    updater.add(controller);
    return controller;
  }

  /**
   * Adds a button to this toolbar.
   */
  public void addClickButton(String icon, final ClickHandler handler) {
    ToolbarClickButton.Listener uiHandler =  new ToolbarClickButton.Listener() {
      @Override
      public void onClicked() {
        handler.onClicked(editor);
      }
    };
    new ToolbarButtonViewBuilder().setIcon(icon).applyTo(toolbarUi.addClickButton(), uiHandler);
  }

  //
  // Event handling.
  //

  /**
   * Starts listening to editor changes.
   */
  public void enable(Editor editor) {
    Preconditions.checkState(this.editor == null);
    Preconditions.checkArgument(editor != null);
    this.editor = editor;
    this.editor.addUpdateListener(this);
    updater.enable();
    updateButtonStates();
  }

  /**
   * Stops listening to editor changes.
   */
  public void disable(Editor editor) {
    Preconditions.checkState(this.editor != null);
    Preconditions.checkArgument(this.editor == editor);
    updater.disable();
    this.editor.removeUpdateListener(this);
    this.editor = null;
  }

  @Override
  public void onUpdate(EditorUpdateEvent event) {
    updateButtonStates();
  }

  /**
   * @return the {@link ToplevelToolbarWidget} backing this toolbar.
   */
  public ToplevelToolbarWidget getWidget() {
    return toolbarUi;
  }

  private void updateButtonStates() {
    Range range = editor.getSelectionHelper().getOrderedSelectionRange();
    if (range != null) {
      updater.invalidate(range);
    }
  }

  private static <E> E[] asArray(E... elements) {
    return elements;
  }
}
