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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public class GuiTokenLogin extends GuiScreen {
    private final GuiScreen previousScreen;

    private GuiTextField tokenField;
    private GuiButton loginButton;
    private GuiButton cancelButton;
    private String status = "§7Enter your Minecraft Access Token§r";
    private ExecutorService executor;
    private CompletableFuture<Void> task;

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    );

    public GuiTokenLogin(GuiScreen previousScreen) {
        this.previousScreen = previousScreen;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);

        this.buttonList.clear();
        this.buttonList.add(loginButton = new GuiButton(
                0, width / 2 - 100, height / 2 + 30, 200, 20, "Login"
        ));
        this.buttonList.add(cancelButton = new GuiButton(
                1, width / 2 - 100, height / 2 + 55, 200, 20, "Cancel"
        ));

        this.tokenField = new GuiTextField(2, this.fontRendererObj,
                width / 2 - 100, height / 2, 200, 20);
        this.tokenField.setMaxStringLength(1000);
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
                case 0: // Login
                    String input = tokenField.getText().trim();
                    if (!input.isEmpty()) {
                        loginWithToken(input);
                    }
                    break;
                case 1: // Cancel
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

        this.tokenField.textboxKeyTyped(typedChar, keyCode);

        if (keyCode == Keyboard.KEY_RETURN) {
            actionPerformed(loginButton);
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
        drawCenteredString(fontRendererObj, "§fLogin with Access Token",
                width / 2, height / 2 - 30, 0xFFFFFF);
        drawCenteredString(fontRendererObj, status,
                width / 2, height / 2 - 15, 0xAAAAAA);

        this.tokenField.drawTextBox();
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void loginWithToken(String input) {
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadExecutor();
        }

        status = "§7Authenticating...§r";
        loginButton.enabled = false;

        String token;
        String usernameFromInput = "";
        String uuidFromInput = "";

        String[] parts = input.split("\\|");
        token = parts[0];

        if (parts.length > 1) {
            usernameFromInput = parts[1];
        }
        if (parts.length > 2) {
            String potentialUuid = parts[2];
            if (UUID_PATTERN.matcher(potentialUuid).matches()) {
                uuidFromInput = potentialUuid;
            } else {
                System.err.println("Invalid UUID format detected: " + potentialUuid + ". Proceeding without provided UUID.");
            }
        }

        CompletableFuture<net.minecraft.util.Session> loginFuture;

        if (!StringUtils.isBlank(usernameFromInput) && !StringUtils.isBlank(uuidFromInput)) {
            loginFuture = MicrosoftAuth.login(token, usernameFromInput, uuidFromInput, executor);
        } else {
            loginFuture = MicrosoftAuth.login(token, executor);
        }

        task = loginFuture
                .thenAcceptAsync(session -> {
                    String finalUsername = session.getUsername();
                    String finalUuid = session.getPlayerID();

                    Optional<Account> existingAccountOptional = AccountManager.accounts.stream()
                            .filter(acc -> acc.getAccessToken().equals(token))
                            .findFirst();

                    Account accountToSave;
                    if (existingAccountOptional.isPresent()) {
                        accountToSave = existingAccountOptional.get();
                        accountToSave.setUsername(finalUsername);
                        accountToSave.setUuid(finalUuid);
                    } else {
                        accountToSave = new Account(finalUsername, token, finalUuid);
                        AccountManager.accounts.add(accountToSave);
                    }

                    AccountManager.save();
                    SessionManager.set(session);

                    mc.addScheduledTask(() -> {
                        mc.displayGuiScreen(new GuiAccountManager(
                                previousScreen,
                                new Notification(
                                        TextFormatting.translate(String.format(
                                                "§aSuccessful login! (%s)§r",
                                                finalUsername
                                        )),
                                        5000L
                                )
                        ));
                    });
                }, executor)
                .exceptionally(error -> {
                    mc.addScheduledTask(() -> {
                        String errorMessage = "Login failed!";
                        if (error != null) {
                            Throwable cause = error.getCause();
                            if (cause != null) {
                                errorMessage = cause.getMessage();
                            } else {
                                errorMessage = error.getMessage();
                            }
                        }
                        status = "§c" + errorMessage;
                        loginButton.enabled = true;
                    });
                    return null;
                });
    }
}