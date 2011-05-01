/**
 * Copyright 2011 Google Inc.
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
package org.waveprotocol.box.webclient.search;

import org.waveprotocol.box.webclient.client.ClientEvents;
import org.waveprotocol.box.webclient.client.events.WaveCreationEvent;
import org.waveprotocol.box.webclient.search.Search.State;
import org.waveprotocol.wave.client.scheduler.Scheduler.IncrementalTask;
import org.waveprotocol.wave.client.scheduler.Scheduler.Task;
import org.waveprotocol.wave.client.scheduler.SchedulerInstance;
import org.waveprotocol.wave.client.scheduler.TimerService;
import org.waveprotocol.wave.client.widget.toolbar.GroupingToolbar;
import org.waveprotocol.wave.client.widget.toolbar.ToolbarButtonViewBuilder;
import org.waveprotocol.wave.client.widget.toolbar.ToolbarView;
import org.waveprotocol.wave.client.widget.toolbar.buttons.ToolbarClickButton;
import org.waveprotocol.wave.model.id.WaveId;
import org.waveprotocol.wave.model.util.CollectionUtils;
import org.waveprotocol.wave.model.util.IdentityMap;

/**
 * Presents a search model into a search view.
 * <p>
 * This class invokes rendering, and controls the lifecycle of digest views. It
 * also handles all UI gesture events sourced from views in the search panel.
 *
 * @author hearnden@google.com (David Hearnden)
 */
public final class SearchPresenter
    implements Search.Listener, SearchPanelView.Listener, SearchView.Listener {

  /**
   * Handles digest selection actions.
   */
  public interface WaveSelectionHandler {
    void onWaveSelected(WaveId id);
  }

  /** How often to repeat the search query. */
  private final static int POLLING_INTERVAL_MS = 10000; // 10s
  private final static String DEFAULT_SEARCH = "in:inbox";
  private final static int DEFAULT_PAGE_SIZE = 20;

  // External references
  private final TimerService scheduler;
  private final Search search;
  private final SearchPanelView searchUi;
  private final WaveSelectionHandler selectionHandler;

  // Internal state
  private final IdentityMap<DigestView, Digest> digestUis = CollectionUtils.createIdentityMap();
  private final IncrementalTask searchUpdater = new IncrementalTask() {
    @Override
    public boolean execute() {
      doSearch();
      return true;
    }
  };

  private final Task renderer = new Task() {
    @Override
    public void execute() {
      if (search.getState() == State.READY) {
        render();
      } else {
        // Try again later.
        scheduler.schedule(this);
      }
    }
  };

  /** Current search query. */
  private String queryText = DEFAULT_SEARCH;
  /** Number of results to query for. */
  private int querySize = DEFAULT_PAGE_SIZE;
  /** Current selected digest. */
  private DigestView selected;

  SearchPresenter(TimerService scheduler, Search search, SearchPanelView searchUi,
      WaveSelectionHandler selectionHandler) {
    this.search = search;
    this.searchUi = searchUi;
    this.scheduler = scheduler;
    this.selectionHandler = selectionHandler;
  }

  /**
   * Creates a search presenter.
   *
   * @param model model to present
   * @param view view to render into
   * @param selectionHandler handler for selection actions
   */
  public static SearchPresenter create(
      Search model, SearchPanelView view, WaveSelectionHandler selectionHandler) {
    SearchPresenter presenter = new SearchPresenter(
        SchedulerInstance.getHighPriorityTimer(), model, view, selectionHandler);
    presenter.init();
    return presenter;
  }

  /**
   * Performs initial presentation, and attaches listeners to live objects.
   */
  private void init() {
    initToolbarMenu();
    initSearchBox();
    render();
    search.addListener(this);
    searchUi.init(this);
    searchUi.getSearch().init(this);

    // Fire a polling search.
    scheduler.scheduleRepeating(searchUpdater, 0, POLLING_INTERVAL_MS);
  }

  /**
   * Releases resources and detaches listeners.
   */
  public void destroy() {
    scheduler.cancel(searchUpdater);
    scheduler.cancel(renderer);
    searchUi.getSearch().reset();
    searchUi.reset();
    search.removeListener(this);
  }

  /**
   * Adds custom buttons to the toolbar.
   */
  private void initToolbarMenu() {
    GroupingToolbar.View toolbarUi = searchUi.getToolbar();
    ToolbarView group = toolbarUi.addGroup();
    new ToolbarButtonViewBuilder().setText("New Wave").applyTo(
        group.addClickButton(), new ToolbarClickButton.Listener() {
          @Override
          public void onClicked() {
            ClientEvents.get().fireEvent(WaveCreationEvent.CREATE_NEW_WAVE);

            // HACK(hearnden): To mimic live search, fire a search poll
            // reasonably soon (500ms) after creating a wave. This will be unnecessary
            // with a real live search implementation. The delay is to give
            // enough time for the wave state to propagate to the server.
            int delay = 500;
            scheduler.scheduleRepeating(searchUpdater, delay, POLLING_INTERVAL_MS);
          }
        });
    // Fake group with empty button - to force the separator be displayed.
    group = toolbarUi.addGroup();
    new ToolbarButtonViewBuilder().setText("").applyTo(group.addClickButton(), null);
  }

  /**
   * Initializes the search box.
   */
  private void initSearchBox() {
    searchUi.getSearch().setQuery(queryText);
  }

  /**
   * Executes the current search.
   */
  private void doSearch() {
    search.find(queryText, querySize);
  }

  /**
   * Renders the current state of the search result into the panel.
   */
  private void render() {
    renderTitle();
    renderDigests();
    renderShowMore();
  }

  /**
   * Renders the paging information into the title bar.
   */
  private void renderTitle() {
    int resultStart = 0;
    int resultEnd = querySize;
    String totalStr;
    if (search.getTotal() != Search.UNKNOWN_SIZE) {
      resultEnd = Math.min(resultEnd, search.getTotal());
      totalStr = String.valueOf(search.getTotal());
    } else {
      totalStr = "unknown";
    }
    searchUi.setTitleText(queryText + " (0-" + resultEnd + " of " + totalStr + ")");
  }

  private void renderDigests() {
    // Preserve selection on re-rendering.
    WaveId toSelect = selected != null ? digestUis.get(selected).getWaveId() : null;
    searchUi.clearDigests();
    digestUis.clear();
    setSelected(null);
    for (int i = 0, size = search.getMinimumTotal(); i < size; i++) {
      Digest digest = search.getDigest(i);
      if (digest == null) {
        continue;
      }
      DigestView digestUi = searchUi.insertBefore(null, digest);
      digestUis.put(digestUi, digest);
      if (digest.getWaveId().equals(toSelect)) {
        setSelected(digestUi);
      }
    }
  }

  private void renderShowMore() {
    searchUi.setShowMoreVisible(
        search.getTotal() == Search.UNKNOWN_SIZE || querySize < search.getTotal());
  }

  //
  // UI gesture events.
  //

  private void setSelected(DigestView digestUi) {
    if (selected != null) {
      selected.deselect();
    }
    selected = digestUi;
    if (selected != null) {
      selected.select();
    }
  }

  /**
   * Invokes the wave-select action on the currently selected digest.
   */
  private void openSelected() {
    selectionHandler.onWaveSelected(digestUis.get(selected).getWaveId());
  }

  @Override
  public void onClicked(DigestView digestUi) {
    setSelected(digestUi);
    openSelected();
  }

  @Override
  public void onQueryEntered() {
    queryText = searchUi.getSearch().getQuery();
    querySize = DEFAULT_PAGE_SIZE;
    searchUi.setTitleText("Searching...");
    doSearch();
  }

  @Override
  public void onShowMoreClicked() {
    querySize += DEFAULT_PAGE_SIZE;
    doSearch();
  }

  //
  // Search events. For now, dumbly re-render the whole list.
  //

  @Override
  public void onStateChanged() {
    //
    // If the state switches to searching, then do nothing. A manual title-bar
    // update is performed in onQueryEntered(), and the title-bar should not be
    // updated when a polling search fires.
    //
    // If the state switches to ready, then just update the title. Do not
    // necessarily re-render, since that is only necessary if a change occurred,
    // which would have fired one of the other methods below.
    //
    if (search.getState() == State.READY) {
      renderTitle();
    }
  }

  @Override
  public void onDigestAdded(int index, Digest digest) {
    renderLater();
  }

  @Override
  public void onDigestRemoved(int index, Digest digest) {
    renderLater();
  }

  @Override
  public void onDigestReady(int index, Digest digest) {
    renderLater();
  }

  @Override
  public void onTotalChanged(int total) {
    renderLater();
  }

  private void renderLater() {
    if (!scheduler.isScheduled(renderer)) {
      scheduler.schedule(renderer);
    }
  }
}
