package com.mewcode.tui;

import com.mewcode.config.ProviderConfig;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.llm.LlmClient;
import com.mewcode.llm.LlmClients;
import com.mewcode.llm.StreamEvent;
import com.mewcode.prompt.PromptBuilder;
import com.mewcode.tui.tea.Command;
import com.mewcode.tui.tea.KeyPressMessage;
import com.mewcode.tui.tea.Message;
import com.mewcode.tui.tea.Model;
import com.mewcode.tui.tea.QuitMessage;
import com.mewcode.tui.tea.UpdateResult;
import com.mewcode.tui.tea.WindowSizeMessage;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.function.BiFunction;

/** Pure-chat TUI model for MewCode's first milestone. */
public final class MewCodeModel implements Model {

    public static final String VERSION = "0.1.0";
    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);
    private static final String[] SPINNER = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};

    private final List<ProviderConfig> providers;
    private final BiFunction<ProviderConfig, String, LlmClient> clientFactory;
    private final ConversationManager conversation = new ConversationManager();
    private final List<ChatMessage> chatMessages = new ArrayList<>();
    private final StringBuilder inputBuffer = new StringBuilder();
    private final StringBuilder streamBuffer = new StringBuilder();

    private AppState state;
    private ProviderConfig selectedProvider;
    private LlmClient client;
    private BlockingQueue<StreamEvent> streamQueue;
    private int providerCursor;
    private int inputCursor;
    private int spinnerFrame;
    private int width = 80;
    private int height = 24;
    private long requestStartMillis;
    private String spinnerVerb = "Imagining";
    private boolean streaming;
    private boolean ready;
    private boolean bannerPrinted;
    private boolean singleProviderPending;
    private String initializationError;

    public record StreamPollMessage() implements Message {}

    public MewCodeModel(List<ProviderConfig> providers) {
        this(providers, LlmClients::create);
    }

    MewCodeModel(List<ProviderConfig> providers,
                 BiFunction<ProviderConfig, String, LlmClient> clientFactory) {
        this.providers = providers == null ? List.of() : List.copyOf(providers);
        this.clientFactory = clientFactory;
        if (this.providers.size() == 1) {
            selectedProvider = this.providers.getFirst();
            state = AppState.CHAT;
            singleProviderPending = true;
        } else {
            state = AppState.PROVIDER_SELECT;
        }
    }

    @Override
    public Command init() {
        return Command.checkWindowSize();
    }

    @Override
    public UpdateResult<MewCodeModel> update(Message message) {
        if (message instanceof KeyPressMessage key && "ctrl+c".equals(key.key())) {
            return UpdateResult.from(this, QuitMessage::new);
        }

        if (message instanceof WindowSizeMessage size) {
            width = Math.max(size.width(), 1);
            height = Math.max(size.height(), 3);
            ready = true;
            if (singleProviderPending) {
                singleProviderPending = false;
                initializeProvider();
            }
            if (state == AppState.CHAT && !bannerPrinted) {
                bannerPrinted = true;
                return UpdateResult.from(this, Command.println(renderBanner()));
            }
            return UpdateResult.from(this);
        }

        if (message instanceof StreamPollMessage) {
            return pollStream();
        }

        if (!(message instanceof KeyPressMessage key)) {
            return UpdateResult.from(this);
        }
        return state == AppState.PROVIDER_SELECT
                ? handleProviderSelection(key)
                : handleChatKey(key);
    }

    @Override
    public String view() {
        if (!ready) return "";
        return state == AppState.PROVIDER_SELECT ? viewProviderSelection() : viewChat();
    }

    private UpdateResult<MewCodeModel> handleProviderSelection(KeyPressMessage message) {
        return switch (message.key()) {
            case "up" -> {
                if (providerCursor > 0) providerCursor--;
                yield UpdateResult.from(this);
            }
            case "down" -> {
                if (providerCursor < providers.size() - 1) providerCursor++;
                yield UpdateResult.from(this);
            }
            case "enter" -> {
                if (providers.isEmpty()) yield UpdateResult.from(this);
                selectedProvider = providers.get(providerCursor);
                initializeProvider();
                state = AppState.CHAT;
                bannerPrinted = true;
                yield UpdateResult.from(this, Command.println(renderBanner()));
            }
            default -> UpdateResult.from(this);
        };
    }

    private void initializeProvider() {
        try {
            client = clientFactory.apply(selectedProvider, PromptBuilder.buildSystemPrompt());
            initializationError = null;
        } catch (RuntimeException error) {
            client = null;
            initializationError = "Provider initialization failed.";
        }
    }

    private UpdateResult<MewCodeModel> handleChatKey(KeyPressMessage message) {
        if (streaming) return UpdateResult.from(this);

        String key = message.key();
        return switch (key) {
            case "enter" -> submit();
            case "alt+enter" -> {
                inputBuffer.insert(inputCursor, '\n');
                inputCursor++;
                yield UpdateResult.from(this);
            }
            case "backspace", "ctrl+h" -> {
                if (inputCursor > 0) {
                    inputBuffer.deleteCharAt(inputCursor - 1);
                    inputCursor--;
                }
                yield UpdateResult.from(this);
            }
            case "left" -> {
                if (inputCursor > 0) inputCursor--;
                yield UpdateResult.from(this);
            }
            case "right" -> {
                if (inputCursor < inputBuffer.length()) inputCursor++;
                yield UpdateResult.from(this);
            }
            case "home", "ctrl+a" -> {
                inputCursor = lineStart(inputBuffer, inputCursor);
                yield UpdateResult.from(this);
            }
            case "end", "ctrl+e" -> {
                inputCursor = lineEnd(inputBuffer, inputCursor);
                yield UpdateResult.from(this);
            }
            default -> insertCharacters(message);
        };
    }

    private UpdateResult<MewCodeModel> insertCharacters(KeyPressMessage message) {
        if (message.runes() == null) return UpdateResult.from(this);
        for (char character : message.runes()) {
            if (character >= 32) {
                inputBuffer.insert(inputCursor, character);
                inputCursor++;
            }
        }
        return UpdateResult.from(this);
    }

    private UpdateResult<MewCodeModel> submit() {
        String text = inputBuffer.toString();
        if (text.isBlank()) return UpdateResult.from(this);
        inputBuffer.setLength(0);
        inputCursor = 0;

        if ("/exit".equals(text.trim())) {
            return UpdateResult.from(this, QuitMessage::new);
        }
        if (client == null) {
            String message = initializationError != null
                    ? initializationError
                    : "No provider is available.";
            chatMessages.add(new ChatMessage("error", message, 0));
            return UpdateResult.from(this, Command.println(renderError(message, 0)));
        }

        chatMessages.add(new ChatMessage("user", text, 0));
        conversation.addUserMessage(text);
        streamBuffer.setLength(0);
        requestStartMillis = System.currentTimeMillis();
        spinnerVerb = SpinnerVerbs.random();
        spinnerFrame = 0;
        streaming = true;

        try {
            streamQueue = client.stream(conversation);
        } catch (RuntimeException error) {
            streamQueue = new java.util.concurrent.LinkedBlockingQueue<>();
            streamQueue.offer(new StreamEvent.Error("Unable to start provider request."));
        }

        return UpdateResult.from(this, Command.batch(
                Command.println(renderUser(text)),
                Command.tick(POLL_INTERVAL, ignored -> new StreamPollMessage())));
    }

    private UpdateResult<MewCodeModel> pollStream() {
        if (!streaming || streamQueue == null) return UpdateResult.from(this);

        spinnerFrame++;
        StreamEvent event;
        while ((event = streamQueue.poll()) != null) {
            switch (event) {
                case StreamEvent.ThinkingDelta ignored -> { }
                case StreamEvent.TextDelta text -> streamBuffer.append(text.text());
                case StreamEvent.StreamEnd end -> {
                    return completeStream(end.stopReason());
                }
                case StreamEvent.Error error -> {
                    return failStream(error.message());
                }
            }
        }
        return UpdateResult.from(this,
                Command.tick(POLL_INTERVAL, ignored -> new StreamPollMessage()));
    }

    private UpdateResult<MewCodeModel> completeStream(String stopReason) {
        String rawText = streamBuffer.toString();
        double elapsed = elapsedSeconds();
        conversation.addAssistantMessage(rawText);
        chatMessages.add(new ChatMessage("assistant", rawText, elapsed));
        String rendered = MarkdownRenderer.render(rawText, Math.max(width - 4, 20));
        resetStream();
        String output = Styles.ASSISTANT.render("● ") + rendered.stripTrailing()
                + "\n" + Styles.DIM.render("  Completed in %.1fs".formatted(elapsed));
        if ("max_tokens".equals(stopReason) || "length".equals(stopReason)) {
            output += "\n" + Styles.DIM.render("  Response stopped at the model output limit.");
        }
        return UpdateResult.from(this, Command.println(output));
    }

    private UpdateResult<MewCodeModel> failStream(String safeMessage) {
        double elapsed = elapsedSeconds();
        var output = new StringBuilder();
        if (!streamBuffer.isEmpty()) {
            output.append(Styles.ASSISTANT.render("● "))
                    .append(safeTerminalText(streamBuffer.toString()))
                    .append("\n")
                    .append(Styles.DIM.render("  Partial response (not added to history)"))
                    .append("\n");
        }
        chatMessages.add(new ChatMessage("error", safeMessage, elapsed));
        output.append(renderError(safeMessage, elapsed));
        resetStream();
        return UpdateResult.from(this, Command.println(output.toString()));
    }

    private void resetStream() {
        streaming = false;
        streamQueue = null;
        streamBuffer.setLength(0);
        spinnerFrame = 0;
    }

    private String viewProviderSelection() {
        var view = new StringBuilder(renderBanner()).append("\n\n")
                .append(Styles.SELECTED.render("Select a provider")).append("\n\n");
        for (int i = 0; i < providers.size(); i++) {
            ProviderConfig provider = providers.get(i);
            String label = provider.getName() + " (" + provider.getModel() + ")";
            view.append(i == providerCursor
                    ? Styles.SELECTED.render("  ❯ " + label)
                    : "    " + label);
            view.append('\n');
        }
        view.append('\n').append(Styles.DIM.render("↑/↓ select · Enter confirm · Ctrl+C quit"));
        return view.toString();
    }

    private String viewChat() {
        var view = new StringBuilder();
        view.append(Styles.DIM.render("● Ready for pure conversation"));
        view.append('\n');

        if (streaming) {
            if (!streamBuffer.isEmpty()) {
                view.append('\n').append(Styles.ASSISTANT.render("● "))
                        .append(safeTerminalText(streamBuffer.toString())).append('\n');
            }
            String frame = SPINNER[spinnerFrame % SPINNER.length];
            view.append('\n').append(Styles.DIM.render(
                    "%s %s… (%.0fs)".formatted(frame, spinnerVerb, elapsedSeconds())));
            view.append('\n');
        } else if (initializationError != null) {
            view.append('\n').append(Styles.ERROR.render("✖ " + initializationError)).append('\n');
        }

        int boxWidth = Math.max(width - 2, 20);
        String border = "─".repeat(boxWidth);
        view.append(Styles.SEPARATOR.render("╭" + border + "╮")).append('\n');
        if (streaming) {
            view.append("│ ").append(Styles.DIM.render("Waiting for response…"));
            view.append(" ".repeat(Math.max(boxWidth - 21, 0))).append("│\n");
        } else {
            appendInput(view);
        }
        view.append(Styles.SEPARATOR.render("╰" + border + "╯")).append('\n');
        view.append(renderStatusBar());
        return view.toString();
    }

    private void appendInput(StringBuilder view) {
        if (inputBuffer.isEmpty()) {
            view.append("│ ").append(Styles.PROMPT.render("❯ "))
                    .append(Styles.DIM.render("Send a message..."));
            int used = 2 + 2 + "Send a message...".length();
            view.append(" ".repeat(Math.max(Math.max(width - 2, 20) - used, 0))).append("│\n");
            return;
        }

        String withCursor = inputBuffer.substring(0, inputCursor) + "█" + inputBuffer.substring(inputCursor);
        String[] lines = withCursor.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            view.append("│ ");
            if (i == 0) view.append(Styles.PROMPT.render("❯ "));
            else view.append("  ");
            view.append(lines[i]);
            int used = 2 + 2 + com.mewcode.tui.tea.Program.displayWidth(lines[i]);
            view.append(" ".repeat(Math.max(Math.max(width - 2, 20) - used, 0))).append("│\n");
        }
    }

    private String renderStatusBar() {
        String left = selectedProvider == null ? "no provider" : selectedProvider.getName();
        String right = selectedProvider == null ? "" : selectedProvider.getModel();
        int spaces = Math.max(width - left.length() - right.length(), 1);
        return Styles.STATUS.render(left + " ".repeat(spaces) + right);
    }

    private String renderBanner() {
        String model = selectedProvider == null ? "" : selectedProvider.getModel();
        String cwd = System.getProperty("user.dir");
        return Styles.BANNER.render(" /\\_/\\    MewCode " + VERSION) + "\n"
                + Styles.BANNER.render("( o.o )   " + model) + "\n"
                + Styles.BANNER.render(" > ^ <    " + cwd);
    }

    private static String renderUser(String text) {
        String[] lines = text.split("\n", -1);
        var result = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) result.append('\n');
            result.append(i == 0 ? Styles.PROMPT.render("❯ ") : "  ")
                    .append(safeTerminalText(lines[i]));
        }
        return result.toString();
    }

    private static String renderError(String message, double elapsed) {
        return Styles.ERROR.render("✖ " + safeTerminalText(message))
                + "\n" + Styles.DIM.render("  Failed in %.1fs".formatted(elapsed));
    }

    private double elapsedSeconds() {
        return Math.max(0, System.currentTimeMillis() - requestStartMillis) / 1000.0;
    }

    private static int lineStart(StringBuilder input, int cursor) {
        int newline = input.lastIndexOf("\n", Math.max(cursor - 1, 0));
        return newline < 0 ? 0 : newline + 1;
    }

    private static int lineEnd(StringBuilder input, int cursor) {
        int newline = input.indexOf("\n", cursor);
        return newline < 0 ? input.length() : newline;
    }

    private static String safeTerminalText(String text) {
        var safe = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\t' || c >= 32 && c != 127) safe.append(c);
        }
        return safe.toString().replace("\033", "");
    }
}
