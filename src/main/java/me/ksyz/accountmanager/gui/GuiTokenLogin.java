package me.ksyz.accountmanager.gui;

import me.ksyz.accountmanager.AccountManager;
import me.ksyz.accountmanager.auth.Account;
import me.ksyz.accountmanager.auth.MicrosoftAuth;
import me.ksyz.accountmanager.auth.SessionManager;
import me.ksyz.accountmanager.utils.Notification;
import me.ksyz.accountmanager.utils.TextFormatting;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import org.apache.commons.lang3.StringUtils;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GuiTokenLogin extends GuiScreen {
    private final GuiScreen previousScreen;

    private GuiTextField tokenField;
    private GuiButton loginButton;
    private GuiButton cancelButton;
    private String status = "§7Enter your Minecraft Access Token(s)§r";
    private ExecutorService executor;
    private CompletableFuture<Void> task;

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    );

    private static final Pattern FULL_FORMAT_TOKEN_PATTERN = Pattern.compile(
            "Accesstoken:([a-zA-Z0-9\\-_\\.]+)"
    );
    private static final Pattern FULL_FORMAT_USERNAME_PATTERN = Pattern.compile(
            "McName:([a-zA-Z0-9_]+)"
    );

    private static final Pattern SIMPLE_FORMAT_PATTERN = Pattern.compile(
            "([a-zA-Z0-9\\-_\\.]+)\\|([a-zA-Z0-9_]+)\\|?([0-9a-fA-F-]{36})?"
    );

    private static final Pattern ACCOUNT_ENTRY_SPLIT_PATTERN = Pattern.compile("(?=\\[Microsoft_Hit\\])");


    public GuiTokenLogin(GuiScreen previousScreen) {
        this.previousScreen = previousScreen;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);

        this.buttonList.clear();
        this.buttonList.add(loginButton = new GuiButton(
                0, width / 2 - 100, height / 2 + 30, 200, 20, "Login Account(s)"
        ));
        this.buttonList.add(cancelButton = new GuiButton(
                1, width / 2 - 100, height / 2 + 55, 200, 20, "Cancel"
        ));

        this.tokenField = new GuiTextField(2, this.fontRendererObj,
                width / 2 - 100, height / 2, 200, 20);
        this.tokenField.setMaxStringLength(50000);
        this.tokenField.setFocused(true);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        if (task != null && !task.isDone()) {
            task.cancel(true);
            if (executor != null && !executor.isShutdown()) {
                executor.shutdownNow();
            }
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.enabled) {
            switch (button.id) {
                case 0:
                    String input = tokenField.getText().trim();
                    if (!input.isEmpty()) {
                        processInputAndLogin(input);
                    } else {
                        status = "§cPlease enter at least one account.§r";
                    }
                    break;
                case 1:
                    mc.displayGuiScreen(previousScreen);
                    break;
            }
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            actionPerformed(cancelButton);
            return;
        }

        if (keyCode == Keyboard.KEY_V && isCtrlKeyDown()) {
            this.tokenField.textboxKeyTyped(typedChar, keyCode);
        } else {
            this.tokenField.textboxKeyTyped(typedChar, keyCode);
        }

        if (keyCode == Keyboard.KEY_RETURN) {
            if (!tokenField.getText().trim().isEmpty()) {
                actionPerformed(loginButton);
            }
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        this.tokenField.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void updateScreen() {
        this.tokenField.updateCursorCounter();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(fontRendererObj, "§fLogin with Access Token(s)",
                width / 2, height / 2 - 30, 0xFFFFFF);
        drawCenteredString(fontRendererObj, status,
                width / 2, height / 2 - 15, 0xAAAAAA);

        this.tokenField.drawTextBox();
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void processInputAndLogin(String fullInput) {
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newFixedThreadPool(5);
        }

        status = "§7Processing accounts...§r";
        loginButton.enabled = false;

        String processedInput = fullInput.replaceAll("\\s+", " ").trim();

        List<String> accountEntries = new ArrayList<>();
        Matcher matcher = ACCOUNT_ENTRY_SPLIT_PATTERN.matcher(processedInput);
        int lastIndex = 0;

        while (matcher.find()) {
            if (matcher.start() > lastIndex) {
                String entry = processedInput.substring(lastIndex, matcher.start()).trim();
                if (!entry.isEmpty()) {
                    accountEntries.add(entry);
                }
            }
            lastIndex = matcher.start();
        }

        if (lastIndex < processedInput.length()) {
            String lastEntry = processedInput.substring(lastIndex).trim();
            if (!lastEntry.isEmpty()) {
                accountEntries.add(lastEntry);
            }
        }

        if (accountEntries.isEmpty() && !processedInput.isEmpty()) {
            accountEntries.add(processedInput);
        }


        List<CompletableFuture<Void>> loginTasks = new ArrayList<>();
        List<String> failedAccounts = new ArrayList<>();
        List<String> successfulAccounts = new ArrayList<>();

        for (String entry : accountEntries) {
            String trimmedEntry = entry.trim();
            if (trimmedEntry.isEmpty()) {
                continue;
            }

            String token = null;
            String usernameFromInput = null;
            String uuidFromInput = null;

            Matcher tokenMatcher = FULL_FORMAT_TOKEN_PATTERN.matcher(trimmedEntry);
            Matcher usernameMatcher = FULL_FORMAT_USERNAME_PATTERN.matcher(trimmedEntry);

            if (tokenMatcher.find()) {
                token = tokenMatcher.group(1);
            }
            if (usernameMatcher.find()) {
                usernameFromInput = usernameMatcher.group(1);
            }

            if (token == null || token.isEmpty()) {
                Matcher simpleFormatMatcher = SIMPLE_FORMAT_PATTERN.matcher(trimmedEntry);
                if (simpleFormatMatcher.find()) {
                    token = simpleFormatMatcher.group(1);
                    if (simpleFormatMatcher.groupCount() >= 2) {
                        usernameFromInput = simpleFormatMatcher.group(2);
                    }
                    if (simpleFormatMatcher.groupCount() >= 3) {
                        String potentialUuid = simpleFormatMatcher.group(3);
                        if (potentialUuid != null && UUID_PATTERN.matcher(potentialUuid).matches()) {
                            uuidFromInput = potentialUuid;
                        } else if (potentialUuid != null) {
                            System.err.println("Invalid UUID format detected in entry (simple format): " + trimmedEntry + ". Proceeding without provided UUID.");
                        }
                    }
                }
            }

            if (token == null || token.isEmpty()) {
                failedAccounts.add("§cInvalid format for: " + (trimmedEntry.length() > 50 ? trimmedEntry.substring(0, 50) + "..." : trimmedEntry) + "§r");
                continue;
            }

            String finalToken = token;
            String finalUsernameFromInput = usernameFromInput;
            String finalUuidFromInput = uuidFromInput;

            CompletableFuture<net.minecraft.util.Session> loginFuture;

            if (!StringUtils.isBlank(finalUsernameFromInput) && !StringUtils.isBlank(finalUuidFromInput)) {
                loginFuture = MicrosoftAuth.login(finalToken, finalUsernameFromInput, finalUuidFromInput, executor);
            } else {
                loginFuture = MicrosoftAuth.login(finalToken, executor);
            }

            CompletableFuture<Void> currentTask = loginFuture
                    .thenAcceptAsync(session -> {
                        String finalUsername = session.getUsername();
                        String finalUuid = session.getPlayerID();

                        Optional<Account> existingAccountOptional = AccountManager.accounts.stream()
                                .filter(acc -> acc.getAccessToken().equals(finalToken))
                                .findFirst();

                        Account accountToSave;
                        if (existingAccountOptional.isPresent()) {
                            accountToSave = existingAccountOptional.get();
                            accountToSave.setUsername(finalUsername);
                            accountToSave.setUuid(finalUuid);
                        } else {
                            accountToSave = new Account(finalUsername, finalToken, finalUuid);
                            AccountManager.accounts.add(accountToSave);
                        }
                        successfulAccounts.add(finalUsername);
                    }, executor)
                    .exceptionally(error -> {
                        String errorMessage = "Login failed!";
                        if (error != null) {
                            Throwable cause = error.getCause();
                            errorMessage = (cause != null ? cause.getMessage() : error.getMessage());
                        }
                        failedAccounts.add("§cFailed (" + errorMessage + ") for: " + (finalUsernameFromInput != null ? finalUsernameFromInput : "Unknown Username/Invalid Token") + "§r");
                        System.err.println("Error processing account: " + trimmedEntry + " - " + errorMessage);
                        return null;
                    });
            loginTasks.add(currentTask);
        }

        task = CompletableFuture.allOf(loginTasks.toArray(new CompletableFuture[0]))
                .thenRunAsync(() -> {
                    AccountManager.save();
                    mc.addScheduledTask(() -> {
                        String finalMessage;
                        if (!successfulAccounts.isEmpty() && failedAccounts.isEmpty()) {
                            finalMessage = String.format("§aSuccessfully logged in %d account(s)!§r", successfulAccounts.size());
                        } else if (successfulAccounts.isEmpty() && !failedAccounts.isEmpty()) {
                            finalMessage = String.format("§cFailed to log in %d account(s).§r", failedAccounts.size());
                        } else {
                            finalMessage = String.format("§aLogged in %d, §cfailed %d account(s).§r", successfulAccounts.size(), failedAccounts.size());
                        }

                        mc.displayGuiScreen(new GuiAccountManager(
                                previousScreen,
                                new Notification(TextFormatting.translate(finalMessage), 5000L)
                        ));

                        if (!failedAccounts.isEmpty()) {
                            System.err.println("Failed account details:");
                            failedAccounts.forEach(System.err::println);
                        }
                    });
                }, executor)
                .exceptionally(totalError -> {
                    mc.addScheduledTask(() -> {
                        status = "§cAn unexpected error occurred during batch processing.§r";
                        loginButton.enabled = true;
                    });
                    return null;
                });
    }
}