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

    private static final Pattern CORE_INFO_EXTRACTION_PATTERN = Pattern.compile(
            "(?:(?:.*?[:|\\s])?Accesstoken:([a-zA-Z0-9\\-_\\.]+))|" +
                    "([a-zA-Z0-9\\-_\\.]+)" +
                    "(?:\\s*\\|McName:([a-zA-Z0-9_]+))?" +
                    "(?:\\s*\\|([a-zA-Z0-9_]+))?" +
                    "(?:\\s*\\|([0-9a-fA-F-]{36}))?"
    );

    private static final Pattern ACCOUNT_FULL_EXTRACTION_PATTERN = Pattern.compile(
            "(?:.*?)?Accesstoken:([a-zA-Z0-9\\-_\\.]+)" +
                    "(?:\\s*\\|McName:([a-zA-Z0-9_]+))?" +
                    "(?:\\s*\\|([a-zA-Z0-9_]+))?" +
                    "(?:\\s*\\|([0-9a-fA-F-]{36}))?" +
                    "|([a-zA-Z0-9\\-_\\.]+)\\|([a-zA-Z0-9_]+)\\|?([0-9a-fA-F-]{36})?",
            Pattern.DOTALL
    );


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

        List<CompletableFuture<Void>> loginTasks = new ArrayList<>();
        List<String> failedAccounts = new ArrayList<>();
        List<String> successfulAccounts = new ArrayList<>();

        Matcher fullExtractorMatcher = ACCOUNT_FULL_EXTRACTION_PATTERN.matcher(fullInput);

        List<String> extractedAccountBlocks = new ArrayList<>();
        while (fullExtractorMatcher.find()) {
            if (fullExtractorMatcher.group(1) != null || fullExtractorMatcher.group(5) != null) {
                extractedAccountBlocks.add(fullExtractorMatcher.group(0));
            }
        }

        if (extractedAccountBlocks.isEmpty() && !fullInput.trim().isEmpty()) {
            extractedAccountBlocks.add(fullInput);
        }


        for (String rawEntry : extractedAccountBlocks) {
            String trimmedEntry = rawEntry.trim();
            if (trimmedEntry.isEmpty()) {
                continue;
            }

            String token = null;
            String usernameFromInput = null;
            String uuidFromInput = null;

            Matcher coreInfoMatcher = CORE_INFO_EXTRACTION_PATTERN.matcher(trimmedEntry);

            while (coreInfoMatcher.find()) {
                if (coreInfoMatcher.group(1) != null) {
                    token = coreInfoMatcher.group(1);
                }
                if (token == null && coreInfoMatcher.group(2) != null) {
                    token = coreInfoMatcher.group(2);
                }

                if (coreInfoMatcher.group(3) != null) {
                    usernameFromInput = coreInfoMatcher.group(3);
                } else if (coreInfoMatcher.group(4) != null) {
                    usernameFromInput = coreInfoMatcher.group(4);
                }

                if (coreInfoMatcher.group(5) != null) {
                    String potentialUuid = coreInfoMatcher.group(5);
                    if (potentialUuid != null && UUID_PATTERN.matcher(potentialUuid).matches()) {
                        uuidFromInput = potentialUuid;
                    }
                }

                if (token != null) {
                    break;
                }
            }


            if (token != null && token.length() < 20 && !token.contains(".")) {
                token = null;
            }

            if (token == null || token.isEmpty()) {
                failedAccounts.add("§cInvalid format or missing token for: " + (trimmedEntry.length() > 50 ? trimmedEntry.substring(0, 50) + "..." : trimmedEntry) + "§r");
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