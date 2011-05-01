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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.inject.Inject;

import jline.ANSIBuffer;
import jline.Completor;
import jline.ConsoleReader;

import org.waveprotocol.box.common.DocumentConstants;
import org.waveprotocol.box.common.IndexEntry;
import org.waveprotocol.box.common.IndexWave;
import org.waveprotocol.box.common.comms.WaveClientRpc.ProtocolSubmitResponse;
import org.waveprotocol.box.consoleclient.ScrollableWaveView.RenderMode;
import org.waveprotocol.box.server.util.BlockingSuccessFailCallback;
import org.waveprotocol.box.server.util.WaveletDataUtil;
import org.waveprotocol.wave.model.id.WaveId;
import org.waveprotocol.wave.model.operation.wave.AddParticipant;
import org.waveprotocol.wave.model.operation.wave.BlipOperation;
import org.waveprotocol.wave.model.operation.wave.RemoveParticipant;
import org.waveprotocol.wave.model.operation.wave.WaveletOperation;
import org.waveprotocol.wave.model.version.HashedVersion;
import org.waveprotocol.wave.model.wave.ParticipantId;
import org.waveprotocol.wave.model.wave.data.BlipData;
import org.waveprotocol.wave.model.wave.data.WaveletData;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * User interface for the console client using the JLine library.
 */
public class ConsoleClient implements WaveletOperationListener {

  /** Interface for any class that can render the console client */
  public interface Renderer {
    /**
     * Renders the console client.
     *
     * @param client to render.
     */
    void render(ConsoleClient client);
  }

  /**
   * Single active client-server interface, or null when not connected to a
   * server.
   */
  private ClientBackend backend = null;

  /**
   * A factory used to construct the client backend instance.
   */
  private final ClientBackend.Factory backendFactory;

  /**
   * Single active console reader.
   */
  private final ConsoleReader reader;

  /**
   * The renderer for this client.
   */
  private final Renderer renderer;

  /**
   * Currently open wave.
   */
  private ScrollableWaveView openWave;

  /**
   * Inbox we are rendering.
   */
  private ScrollableInbox inbox;

  /**
   * Number of lines to scroll by with { and }.
   */
  private final AtomicInteger scrollLines = new AtomicInteger(1);

  /**
   * PrintStream to use for output.  We don't use ConsoleReader's functionality
   * because it's too verbose and doesn't really give us anything in return.
   */
  private final PrintStream out = System.out;


  private class Command {

    public final String name;
    public final String args;
    public final String description;

    private Command(String name, String args, String description) {
      this.name = name;
      this.args = args;
      this.description = description;
    }
  }

  /**
   * Commands available to the user.
   */
  private final List<Command> commands = ImmutableList.of(
      new Command("connect", "user@domain server:port [password]",
                  "connect to server:port as user@domain, optionally with specified password"),
      new Command("open", "entry", "open a wave given an inbox entry"),
      new Command("new", "", "create a new wave"),
      new Command("add", "user@domain", "add a user to a wave"),
      new Command("remove", "user@domain", "remove a user from a wave"),
      new Command("read", "", "set all waves as read"),
// Temporarily disabled:
//      new Command("undo", "[user@domain]",
//                  "undo last line by a user, defaulting to current user"),
      new Command("scroll", "lines",
                  "set the number of lines to scroll by with { and }"),
      new Command("view", "mode",
                  "change view mode for the open wavelet (normal, xml)"),
      new Command("log", "", "dump the log to the screen"),
      new Command("dumplog", "file", "dump the log to a file"),
      new Command("clearlog", "", "clear the log"),
      new Command("quit", "", "quit the client"));

  /**
   * The default console client renderer
   */
  public static class DefaultRenderer implements Renderer {
    @Override
    public void render(ConsoleClient client) {
      final ConsoleReader reader = client.reader;
      final ScrollableInbox inbox = client.inbox;
      final ScrollableWaveView openWave = client.openWave;
      final int canvasWidth = getCanvasWidth(reader);
      final int canvasHeight = getCanvasHeight(reader);
      final StringBuilder buf = new StringBuilder();

      // Clear screen.
      buf.append(ANSIBuffer.ANSICodes.save());
      buf.append(ANSIBuffer.ANSICodes.gotoxy(1, 1));
      buf.append(((char) 27) + "[J");

      // Render inbox and wave size by side.
      List<String> inboxRender;
      List<String> waveRender;

      if (inbox != null) {
        inbox.setOpenWave((openWave == null) ? null : openWave.getWave());
        inboxRender = inbox.render(canvasWidth, canvasHeight);
      } else {
        inboxRender = Lists.newArrayList();
        ConsoleUtils.ensureHeight(canvasWidth, canvasHeight, inboxRender);
      }

      if (openWave != null) {
        waveRender = openWave.render(canvasWidth, canvasHeight);
      } else {
        waveRender = Lists.newArrayList();
        ConsoleUtils.ensureHeight(canvasWidth, canvasHeight, waveRender);
      }

      buf.append(renderSideBySide(inboxRender, waveRender));

      // Draw what the user was typing at the time of rendering.
      buf.append(ANSIBuffer.ANSICodes.gotoxy(reader.getTermheight(), 1));
      buf.append(reader.getDefaultPrompt());
      buf.append(reader.getCursorBuffer());

      // Restore cursor.
      buf.append(ANSIBuffer.ANSICodes.restore());

      System.out.print(buf);
      System.out.flush();
    }

    /**
    * @return the width of the "canvas", how wide a single rendering panel is
    */
    private int getCanvasWidth(ConsoleReader reader) {
      return (reader.getTermwidth() / 2) - 2; // There are 2 panels, then leave some space.
    }

    /**
    * @return the height of the "canvas", how high a single rendering panel is
    */
    private int getCanvasHeight(ConsoleReader reader) {
      return reader.getTermheight() - 1; // Subtract a line for the input.
    }

    /**
    * Render two list of Strings (lines) side by side.
    *
    * @param left  column
    * @param right column
    * @return rendered columns
    */
    private String renderSideBySide(List<String> left, List<String> right) {
      StringBuilder rendered = new StringBuilder();

      if (left.size() != right.size()) {
        throw new IllegalArgumentException("Left and right are different heights");
      }

      for (int i = 0; i < left.size(); i++) {
        rendered.append(left.get(i));
        rendered.append(" | ");
        rendered.append(right.get(i));
        rendered.append("\n");
      }

      return rendered.toString();
    }
  }

  /**
   * Create new console client with the default backend factory and renderer.
   */
  public ConsoleClient() throws IOException {
    this(new ClientBackend.DefaultFactory(), new DefaultRenderer());
  }

  /**
   * Create new console client with the given backend factory and renderer.
   */
  @Inject
  public ConsoleClient(ClientBackend.Factory backendFactory, Renderer renderer)
      throws IOException {
    reader = new ConsoleReader();
    this.backendFactory = backendFactory;
    this.renderer = renderer;

    // Set up scrolling -- these are the opposite to how you would expect because of the way that
    // the waves are scrolled (where the bottom is treated as the top).
    reader.addTriggeredAction('}', new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent e) {
        if (isWaveOpen()) {
          openWave.scrollUp(scrollLines.get());
          render();
        }
      }
    });

    reader.addTriggeredAction('{', new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent e) {
        if (isWaveOpen()) {
          openWave.scrollDown(scrollLines.get());
          render();
        }
      }
    });

    // And tab completion.
    reader.addCompletor(new Completor() {
      @SuppressWarnings("unchecked")
      @Override
      public int complete(String buffer, int cursor, List candidates) {
        if (buffer.trim().startsWith("/")) {
          buffer = buffer.trim().substring(1);
        }

        for (Command cmd : commands) {
          if (cmd.name.startsWith(buffer)) {
            candidates.add('/' + cmd.name + ' ');
          }
        }

        return 0;
      }
    });
  }

  /**
   * Entry point for the user interface, receives user input, terminates on
   * EOF.
   *
   * @param args command line arguments
   */
  public void run(String[] args) throws IOException {
    // Initialise screen and move cursor to bottom left corner.
    reader.clearScreen();
    reader.setDefaultPrompt("(not connected) ");
    out.println(ANSIBuffer.ANSICodes.gotoxy(reader.getTermheight(), 1));

    // Immediately establish connection if desired, otherwise the user will need to use "/connect".
    if (args.length == 2) {
      connect(args[0], null, args[1]);
    } else if (args.length != 0) {
      System.out.println("Usage: java ConsoleClient [user@domain server:port]");
      shutdown(1);
    }

    for (String line = reader.readLine(); line != null; line = reader.readLine()) {
      processLine(line);
    }

    shutdown(0);
  }

  /**
   * Processes the given command line and performs the appropriate action
   *
   * @param line command line string
   */
  @VisibleForTesting
  void processLine(String line) {
    if (line.startsWith("/")) {
      doCommand(extractCmd(line), extractArgs(line));
    } else if (line.length() > 0) {
      sendAppendBlipDelta(line);
    } else {
      if (isWaveOpen()) {
        openWave.scrollToTop();
      }
      render();
    }
  }

  /**
   * Extract the command from a command line String.
   *
   * For example, extractCmd("/connect hello") returns "connect".
   *
   * @param commandLine the command line input
   * @return command
   */
  private String extractCmd(String commandLine) {
    return extractCmdBits(commandLine).get(0).substring(1);
  }

  private char[] readPassword() throws IOException {
    // We can't use the reader without resetting the prompt.
    String oldPrompt = reader.getDefaultPrompt();
    char[] pwd = reader.readLine("Enter password: ", (char) 0).toCharArray();
    reader.setDefaultPrompt(oldPrompt);
    return pwd;
  }

  /**
   * Extract the command arguments from a command line String.
   *
   * For example, extractArgs("/connect hello") returns ["hello"].
   *
   * @param commandLine the command line input
   * @return list of command arguments
   */
  private List<String> extractArgs(String commandLine) {
    List<String> bits = extractCmdBits(commandLine);
    return bits.subList(1, bits.size());
  }

  /**
   * Extract a list of command line components from a command line String.
   *
   * For example, extractCmdBits("/connect hello") return ["/connect",
   * "hello"].
   *
   * @param commandLine the command line input
   * @return list of command line components
   */
  private List<String> extractCmdBits(String commandLine) {
    return Arrays.asList(commandLine.trim().split(" +"));
  }

  /**
   * Perform command with given arguments.
   *
   * @param cmd  command string to perform
   * @param args list of arguments to the command
   */
  private void doCommand(String cmd, List<String> args) {
    if (cmd.equals("connect")) {
      if (args.size() == 2) {
        connect(args.get(0), null, args.get(1));
      } else if (args.size() == 3) {
        // This is used in testing to make sure we don't actually bug the user.
        connect(args.get(0), args.get(2).toCharArray(), args.get(1));
      } else {
        badArgs(cmd);
      }
    } else if (cmd.equals("open")) {
      if (args.size() == 1) {
        try {
          doOpenWave(Integer.parseInt(args.get(0)));
        } catch (NumberFormatException e) {
          out.println("Error: " + args.get(0) + " is not a number");
        }
      } else {
        badArgs(cmd);
      }
    } else if (cmd.equals("new")) {
      newWave();
    } else if (cmd.equals("add")) {
      if (args.size() == 1) {
        addParticipant(args.get(0));
      } else {
        badArgs(cmd);
      }
    } else if (cmd.equals("remove")) {
      if (args.size() == 1) {
        removeParticipant(args.get(0));
      } else {
        badArgs(cmd);
      }
    } else if (cmd.equals("view")) {
      if (args.size() == 1) {
        setView(args.get(0));
      } else {
        badArgs(cmd);
      }
    } else if (cmd.equals("read")) {
      readAllWaves();
// "Undo" temporarily disabled.
//    } else if (cmd.equals("undo")) {
//      if (args.size() == 1) {
//        undo(args.get(0));
//      } else if (backend != null) {
//        undo(backend.getUserId().getAddress());
//      } else {
//        errorNotConnected();
//      }
    } else if (cmd.equals("scroll")) {
      if (args.size() == 1) {
        setScrollLines(args.get(0));
      } else {
        badArgs(cmd);
      }
    } else if (cmd.equals("log")) {
      out.print(ClientBackend.getLog());
    } else if (cmd.equals("dumplog")) {
      if (args.size() == 1) {
        try {
          new PrintStream(args.get(0)).print(ClientBackend.getLog());
        } catch (IOException e) {
          out.println("Couldn't write log to " + args.get(0) + ": " + e);
        }
      } else {
        badArgs(cmd);
      }
    } else if (cmd.equals("clearlog")) {
      ClientBackend.clearLog();
    } else if (cmd.equals("quit")) {
      shutdown(0);
    } else {
      printHelp();
    }
  }

  /**
   * Print help.
   */
  private void printHelp() {
    int maxNameLength = 0;
    int maxArgsLength = 0;

    for (Command cmd : commands) {
      maxNameLength = Math.max(maxNameLength, cmd.name.length());
      maxArgsLength = Math.max(maxArgsLength, cmd.args.length());
    }

    out.println("Commands:");
    for (Command cmd : commands) {
      out.printf(String.format("  %%-%ds  %%-%ds  %%s\n", maxNameLength, maxArgsLength), cmd.name,
          cmd.args, cmd.description);
    }

    out.println();
    out.println("Scrolling:");
    out.println("  {  scroll up open wave");
    out.println("  }  scroll down open wave");
    out.println();
  }

  /**
   * Print some error message when there are bad arguments to a user interface
   * command.
   *
   * @param cmd the bad command
   */
  private void badArgs(String cmd) {
    out.println("Error: incorrect number of arguments to " + cmd + ", expecting: /" + cmd + " "
        + findCommand(cmd).args);
  }

  /**
   * @param command name
   * @return the {@code Command} object from commands for command, or null if
   *         not found
   */
  private Command findCommand(String command) {
    for (Command cmd : commands) {
      if (command.equals(cmd.name)) {
        return cmd;
      }
    }

    return null;
  }

  /**
   * Attempt to login. Blocks until login complete.
   *
   * Returns false if authentication fails, true otherwise.
   *
   * If suppliedPassword is set, it will be used to login.
   * If suppliedPassword is null, the user will be queried for a password. They are
   * given 3 attempts to login, after which the method returns false.
   *
   * @param suppliedPassword A password to login with, or null.
   * @return true if login succeeded, false otherwise.
   */
  private boolean login(char[] suppliedPassword) throws IOException {
    boolean authResult = false;
    int attempts = 0;

    do {
      char[] password = suppliedPassword != null ? suppliedPassword : readPassword();
      authResult = backend.authenticate(password);

      if (authResult == false) {
        out.println("Login failed.");

        // We'll give the user 3 attempts to login.
        attempts++;
      }
    } while (authResult == false && suppliedPassword == null && attempts < 3);

    return authResult;
  }

  /**
   * Register a user and server with a new {@link ClientBackend}.
   *
   * @param suppliedPassword the user's password, or null to have connect()
   *        query the user automatically. If supplied password is set, the user
   *        is never queried.
   */
  private void connect(final String userAtDomain, char[] suppliedPassword, String serverAddress) {
    // We can only connect to one server at a time (at least, in this simple UI).
    if (isConnected()) {
      out.println("Warning: already connected. Disconnecting.");
      disconnect();
    }

    try {
      backend = backendFactory.create(userAtDomain, serverAddress);
      backend.addWaveletOperationListener(this);
    } catch (IOException e) {
      out.println("Error: failed to connect, " + e.getMessage());
      return;
    }

    try {
      if (!login(suppliedPassword)) {
        out.println("Error: Authentication failed. Username / password invalid.");
        return;
      }
    } catch (IOException e) {
      out.println("Error: failed to contact to authentication server: " + e.getMessage());
      return;
    }

    reader.setDefaultPrompt(
        ConsoleUtils.ansiWrap(ConsoleUtils.ANSI_RED_FG, userAtDomain + "> "));
    inbox = new ScrollableInbox(backend, backend.getIndexWave());

    render();
  }

  /**
   * Disconnects the client
   */
  @VisibleForTesting
  void disconnect() {
    if (backend != null) {
      backend.shutdown();
      backend = null;
      openWave = null;
      inbox = null;
    }
  }

  /**
   * @return the client backend.
   */
  @VisibleForTesting
  ClientBackend getBackend() {
    return backend;
  }

  /**
   * @return the wave ID of the currently open wave.
   */
  @VisibleForTesting
  WaveId getOpenWaveId() {
    // Throw NPE if no wave is open.
    return openWave.getWave().getWaveId();
  }

  /**
   * @return the wave ID of the currently open wave.
   */
  @VisibleForTesting
  ScrollableWaveView.RenderMode getRenderingMode() {
    // Throw NPE if no wave is open.
    return openWave.getRenderingMode();
  }

  /**
   * @return the current number of lines to scroll by with { and }.
   */
  @VisibleForTesting
  int getScrollLines() {
    return scrollLines.get();
  }

  /**
   * @return true if the inbox is open.
   */
  @VisibleForTesting
  boolean isInboxOpen() {
    return (inbox != null);
  }

  /**
   * Checks if the specified wave has been read.
   *
   * @param wave to check.
   * @return true if the wave was read.
   */
  @VisibleForTesting
  boolean isRead(ClientWaveView wave) {
    return (isInboxOpen()) && inbox.isRead(wave);
  }

  /**
   * Shut down the client and exits
   *
   * @param exitStatus to quit with
   */
  private void shutdown(int exitStatus) {
    disconnect();
    System.exit(exitStatus);
  }

  /**
   * Create and send a mutation that creates a new blip containing the given text, places it in a
   * new blip, then adds a referece to the blip in the document manifest.
   *
   * @param text the text to include in the new blip
   */
  private void sendAppendBlipDelta(String text) {
    if (isWaveOpen()) {
      WaveletOperation[] ops = ClientUtils.createAppendBlipOps(getManifestDocument(),
          backend.getIdGenerator().newBlipId(), text, backend.createOperationContext());
      backend.sendAndAwaitWaveletOperations(WaveletDataUtil.waveletNameOf(getOpenWavelet()), 1,
          TimeUnit.MINUTES, ops);
    } else {
      errorNoWaveOpen();
    }
  }

  /**
   * @return open document, or null if no wave is open or main document doesn't
   *         exist
   */
  private BlipData getManifestDocument() {
    return getOpenWavelet() == null ? null : getOpenWavelet().getDocument(
        DocumentConstants.MANIFEST_DOCUMENT_ID);
  }

  /**
   * @return the open wavelet of the open wave, or null if no wave is open
   */
  private WaveletData getOpenWavelet() {
    return isWaveOpen() ? ClientUtils.getConversationRoot(openWave.getWave()) : null;
  }

  /**
   * Open a wave with a given entry (index in the inbox).
   *
   * @param entry into the inbox
   */
  private void doOpenWave(int entry) {
    if (isConnected()) {
      List<IndexEntry> index = IndexWave.getIndexEntries(backend.getIndexWave().getWavelets());

      if (entry >= index.size()) {
        out.print("Error: entry is out of range, ");
        if (index.isEmpty()) {
          out.println("there are no available waves (try \"/new\")");
        } else {
          out.println("expecting [0.." + (index.size() - 1) + "] (for example, \"/open 0\")");
        }
      } else {
        setOpenWave(backend.getWave(index.get(entry).getWaveId()));
      }
    } else {
      errorNotConnected();
    }
  }

  /**
   * Set a wave as the open wave.
   *
   * @param wave to set as open
   */
  private void setOpenWave(ClientWaveView wave) {
    if (ClientUtils.getConversationRoot(wave) == null) {
      openWave = null;
    } else {
      openWave = new ScrollableWaveView(wave);
    }
    render();
  }

  /**
   * Add a new wave.
   */
  private void newWave() {
    if (isConnected()) {
      BlockingSuccessFailCallback<ProtocolSubmitResponse, String> callback =
          BlockingSuccessFailCallback.create();
      backend.createConversationWave(callback);
      callback.await(1, TimeUnit.MINUTES);
    } else {
      errorNotConnected();
    }
  }

  /**
   * Add a participant to the currently open wave(let).
   *
   * @param name name of the participant to add
   */
  private void addParticipant(String name) {
    if (isWaveOpen()) {
      name = ensureHasDomain(name);
      ParticipantId addId = ParticipantId.ofUnsafe(name);

      // Don't send an invalid op, although the server should be robust enough
      // to deal with it
      if (!getOpenWavelet().getParticipants().contains(addId)) {
        backend.sendAndAwaitWaveletOperations(WaveletDataUtil.waveletNameOf(getOpenWavelet()),
            1, TimeUnit.MINUTES, new AddParticipant(backend.createOperationContext(), addId));
      } else {
        out.println("Error: " + name + " is already a participant on this wave");
      }
    } else {
      errorNoWaveOpen();
    }
  }

  /**
   * Remove a participant from the currently open wave(let).
   *
   * @param name name of the participant to remove
   */
  private void removeParticipant(String name) {
    if (isWaveOpen()) {
      name = ensureHasDomain(name);
      ParticipantId removeId = ParticipantId.ofUnsafe(name);

      if (getOpenWavelet().getParticipants().contains(removeId)) {
        backend.sendAndAwaitWaveletOperations(WaveletDataUtil.waveletNameOf(getOpenWavelet()),
            1, TimeUnit.MINUTES, new RemoveParticipant(backend.createOperationContext(), removeId));
      } else {
        out.println("Error: " + name + " is not a participant on this wave");
      }
    } else {
      errorNoWaveOpen();
    }
  }

  /**
   * Ensures that a domain name is present in the returned string, by either
   * returning the given string or by appending the domain name of the local
   * user.
   *
   * @param address the address to ensure has a domain
   * @return {@link String} which at least contains the domain prefix.
   */
  private String ensureHasDomain(String address) {
    // Ensure that the given name has an explicit domain
    if (!address.contains(ParticipantId.DOMAIN_PREFIX)) {
      // No domain so add the domain of the local user
      String localDomain = backend.getUserId().getDomain();
      address += ParticipantId.DOMAIN_PREFIX + localDomain;
    }
    return address;
  }

  /**
   * Set the view type for the open wavelet.
   *
   * @param mode for rendering
   */
  private void setView(String mode) {
    if (isWaveOpen()) {
      if (mode.equals("normal")) {
        openWave.setRenderingMode(RenderMode.NORMAL);
        render();
      } else if (mode.equals("xml")) {
        openWave.setRenderingMode(RenderMode.XML);
        render();
      } else {
        out.println("Error: unsupported rendering, run \"?\"");
      }
    } else {
      errorNoWaveOpen();
    }
  }

  /**
   * Set all waves as read.
   */
  private void readAllWaves() {
    if (isConnected()) {
      inbox.markAllAsRead();
      render();
    } else {
      errorNotConnected();
    }
  }

// "Undo" temporarily disabled.

//  /**
//   * Undo last line (line elements and text) sent by a user.
//   *
//   * @param userId of user
//   */
//  private void undo(String userId) {
//    if (isWaveOpen()) {
//      if (getOpenWavelet().getParticipants()
//          .contains(new ParticipantId(userId))) {
//        undoLastLineBy(userId);
//      } else {
//        out.println("Error: " + userId + " is not a participant of this wave");
//      }
//    } else {
//      errorNoWaveOpen();
//    }
//  }

//  /**
//   * Do the real work for undo.
//   *
//   * @param userId of user
//   */
//  private void undoLastLineBy(final String userId) {
//    if (getOpenDocument() == null) {
//      out.println("Error: document is empty");
//      return;
//    }
//
//    // Find the last line written by the participant given by userId (by counting the number of
//    // <line></line> elements, and comparing to their authors).
//    final AtomicInteger totalLines = new AtomicInteger(0);
//    final AtomicInteger lastLine = new AtomicInteger(-1);
//
//    getOpenDocument().apply(new InitializationCursorAdapter(
//        new DocInitializationCursor() {
//          @Override
//          public void elementStart(String type, Attributes attrs) {
//            if (type.equals(ConsoleUtils.LINE)) {
//              totalLines.incrementAndGet();
//
//              if (userId.equals(attrs.get(ConsoleUtils.LINE_AUTHOR))) {
//                lastLine.set(totalLines.get() - 1);
//              }
//            }
//          }
//
//          @Override
//          public void characters(String s) {
//          }
//
//          @Override
//          public void annotationBoundary(AnnotationBoundaryMap map) {
//          }
//
//          @Override
//          public void elementEnd() {
//          }
//        }));
//
//    // Delete the line
//    if (lastLine.get() >= 0) {
//      WaveletDocumentOperation
//          undoOp =
//          new WaveletDocumentOperation(MAIN_DOCUMENT_ID,
//                                       ConsoleUtils.createLineDeletion(
//                                           getOpenDocument(), lastLine.get()));
//      backend.sendWaveletOperation(getOpenWavelet().getWaveletName(), undoOp);
//    } else {
//      out.println("Error: " + userId + " hasn't written anything yet");
//    }
//  }

  /**
   * Set the number of lines to scroll by.
   *
   * @param lines to scroll by
   */
  public void setScrollLines(String lines) {
    try {
      scrollLines.set(Integer.parseInt(lines));
    } catch (NumberFormatException e) {
      out.println("Error: lines must be a valid integer");
    }
  }

  /**
   * Print error message if user is not connected to a server.
   */
  private void errorNotConnected() {
    out.println("Error: not connected, run \"/connect user@domain server:port\"");
  }

  /**
   * Print error message if user does not have a wave open.
   */
  private void errorNoWaveOpen() {
    out.println("Error: no wave is open, run \"/open index\"");
  }

  /**
   * @return whether the client is connected to any server
   */
  @VisibleForTesting
  boolean isConnected() {
    return backend != null;
  }

  /**
   * @return whether the client has a wave open (and displayed in the UI)
   */
  @VisibleForTesting
  boolean isWaveOpen() {
    return (openWave != null);
  }

  /**
   * Render everything (inbox, open wave, input).
   */
  private void render() {
    renderer.render(this);
  }

  @Override
  public void waveletDocumentUpdated(String author, WaveletData wavelet, String docId,
      BlipOperation docOp) {
    // TODO(arb): record the author??
  }

  @Override
  public void participantAdded(String author, WaveletData wavelet, ParticipantId participantId) {
  }

  @Override
  public void participantRemoved(String author, WaveletData wavelet, ParticipantId participantId) {
    if (isWaveOpen() && participantId.equals(backend.getUserId())) {
      // We might have been removed from our open wave (an impressively verbose check...)
      if (wavelet.getWaveId().equals(openWave.getWave().getWaveId())) {
        openWave = null;
      }
    }
  }

  @Override
  public void noOp(String author, WaveletData wavelet) {
  }

  @Override
  public void onDeltaSequenceStart(WaveletData wavelet) {
  }

  @Override
  public void onDeltaSequenceEnd(WaveletData wavelet) {
    render();
  }

  @Override
  public void onCommitNotice(WaveletData wavelet, HashedVersion version) {
  }

  public static void main(String[] args) {
    try {
      ConsoleClient ui = new ConsoleClient();
      ui.run(args);
    } catch (IOException e) {
      System.err.println("IOException when running client: " + e);
    }
  }
}
