package me.ksyz.accountmanager.gui;

import me.ksyz.accountmanager.AccountManager;
import me.ksyz.accountmanager.auth.Account;
import me.ksyz.accountmanager.auth.MicrosoftAuth;
import me.ksyz.accountmanager.auth.SessionManager;
import me.ksyz.accountmanager.utils.Notification;
import me.ksyz.accountmanager.utils.SystemUtils;
import me.ksyz.accountmanager.utils.TextFormatting;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import org.apache.commons.lang3.RandomStringUtils;
import org.lwjgl.input.Keyboard;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

public class GuiMicrosoftAuth extends GuiScreen {
  private final GuiScreen previousScreen;
  private final String state;

  private GuiButton openButton = null;
  private GuiButton copyButton = null;
  private GuiButton cancelButton = null;
  private boolean openButtonEnabled = true;
  private String status = null;
  private String cause = null;
  private ExecutorService executor = null;
  private CompletableFuture<Void> task = null;
  private boolean success = false;

  private long lastDotUpdateTime;
  private int dotCount;
  private static final long DOT_ANIMATION_INTERVAL = 200L;

  public GuiMicrosoftAuth(GuiScreen previousScreen) {
    this.previousScreen = previousScreen;
    this.state = RandomStringUtils.randomAlphanumeric(8);
    this.lastDotUpdateTime = System.currentTimeMillis();
    this.dotCount = 0;
  }

  @Override
  public void initGui() {
    buttonList.clear();
    int buttonWidth = 200;
    int buttonHeight = 20;
    int spacing = 5;
    int centerX = width / 2;
    int startX = centerX - buttonWidth / 2;
    int baseY = height / 2 + fontRendererObj.FONT_HEIGHT / 2 + fontRendererObj.FONT_HEIGHT * 2;

    buttonList.add(openButton = new GuiButton(
            0,
            startX,
            baseY,
            buttonWidth,
            buttonHeight,
            "Open Link"
    ));
    buttonList.add(copyButton = new GuiButton(
            1,
            startX,
            baseY + buttonHeight + spacing,
            buttonWidth,
            buttonHeight,
            "Copy Link"
    ));
    buttonList.add(cancelButton = new GuiButton(
            2,
            startX,
            baseY + (buttonHeight + spacing) * 2,
            buttonWidth,
            buttonHeight,
            "Cancel"
    ));

    if (task == null) {
      status = "&fWaiting for login&r";

      if (executor == null) {
        executor = Executors.newSingleThreadExecutor();
      }
      AtomicReference<String> refreshTokenRef = new AtomicReference<>("");
      AtomicReference<String> accessTokenRef = new AtomicReference<>("");
      AtomicReference<String> uuidRef = new AtomicReference<>("");

      task = MicrosoftAuth.acquireMSAuthCode(state, executor)
              .thenComposeAsync(msAuthCode -> {
                openButtonEnabled = false;
                status = "&fAcquiring Microsoft access tokens&r";
                return MicrosoftAuth.acquireMSAccessTokens(msAuthCode, executor);
              })
              .thenComposeAsync(msAccessTokens -> {
                status = "&fAcquiring Xbox access token.&r";
                refreshTokenRef.set(msAccessTokens.get("refresh_token"));
                return MicrosoftAuth.acquireXboxAccessToken(msAccessTokens.get("access_token"), executor);
              })
              .thenComposeAsync(xboxAccessToken -> {
                status = "&fAcquiring Xbox XSTS token&r";
                return MicrosoftAuth.acquireXboxXstsToken(xboxAccessToken, executor);
              })
              .thenComposeAsync(xboxXstsData -> {
                status = "&fAcquiring Minecraft access token&r";
                return MicrosoftAuth.acquireMCAccessToken(
                        xboxXstsData.get("Token"), xboxXstsData.get("uhs"), executor
                );
              })
              .thenComposeAsync(mcToken -> {
                status = "&fFetching your Minecraft profile&r";
                accessTokenRef.set(mcToken);
                return MicrosoftAuth.login(mcToken, executor);
              })
              .thenAccept(session -> {
                status = null;
                cause = null;

                Account acc = new Account(
                        refreshTokenRef.get(),
                        accessTokenRef.get(),
                        session.getUsername(),
                        session.getPlayerID()
                );

                for (Account account : AccountManager.accounts) {
                  if (acc.getUsername().equals(account.getUsername())) {
                    acc.setUnban(account.getUnban());
                    break;
                  }
                }
                AccountManager.accounts.add(acc);
                AccountManager.save();
                SessionManager.set(session);
                success = true;
              })
              .exceptionally(error -> {
                openButtonEnabled = true;
                status = String.format("&cLogin failed!&r");
                cause = error.getCause() != null && error.getCause().getMessage() != null
                        ? String.format("&cReason: %s&r", error.getCause().getMessage())
                        : String.format("&cUnknown error occurred.&r");
                return null;
              });
    }
  }

  @Override
  public void onGuiClosed() {
    if (task != null && !task.isDone()) {
      task.cancel(true);
      executor.shutdownNow();
    }
  }

  @Override
  public void updateScreen() {
    if (success) {
      mc.displayGuiScreen(new GuiAccountManager(
              previousScreen,
              new Notification(
                      TextFormatting.translate(String.format(
                              "&aSuccessful login! (%s)&r",
                              SessionManager.get().getUsername()
                      )),
                      5000L
              )
      ));
      success = false;
    }

    if (status != null && !success && task != null && !task.isDone()) {
      long currentTime = System.currentTimeMillis();
      if (currentTime - lastDotUpdateTime >= DOT_ANIMATION_INTERVAL) {
        dotCount = (dotCount + 1) % 4;
        lastDotUpdateTime = currentTime;
      }
    } else {
      dotCount = 0;
    }
  }

  @Override
  public void drawScreen(int mouseX, int mouseY, float partialTicks) {
    if (openButton != null) {
      openButton.enabled = openButtonEnabled;
    }
    if (copyButton != null) {
      copyButton.enabled = openButtonEnabled;
    }

    drawDefaultBackground();
    super.drawScreen(mouseX, mouseY, partialTicks);

    drawCenteredString(
            fontRendererObj, "Microsoft Authentication",
            width / 2, height / 2 - fontRendererObj.FONT_HEIGHT / 2 - fontRendererObj.FONT_HEIGHT * 2, 11184810
    );

    if (status != null) {
      String displayedStatus = status;
      if (task != null && !task.isDone() && cause == null) {
        for (int i = 0; i < dotCount; i++) {
          displayedStatus += ".";
        }
      }
      drawCenteredString(
              fontRendererObj, TextFormatting.translate(displayedStatus),
              width / 2, height / 2 - fontRendererObj.FONT_HEIGHT / 2, -1
      );
    }

    if (cause != null) {
      drawCenteredString(
              fontRendererObj, TextFormatting.translate(cause),
              width / 2, height / 2 + fontRendererObj.FONT_HEIGHT / 2 + fontRendererObj.FONT_HEIGHT, 0xFFAAAA
      );
    }
  }

  @Override
  protected void keyTyped(char typedChar, int keyCode) {
    if (keyCode == Keyboard.KEY_ESCAPE) {
      actionPerformed(cancelButton);
    }
  }

  @Override
  protected void actionPerformed(GuiButton button) {
    if (button == null) {
      return;
    }

    if (button.enabled) {
      switch (button.id) {
        case 0: {
          SystemUtils.openWebLink(MicrosoftAuth.getMSAuthLink(state));
          status = "&fPlease complete the login in your browser&r";
          cause = null;
          lastDotUpdateTime = System.currentTimeMillis();
          dotCount = 0;
        }
        break;
        case 1: {
          URI url = MicrosoftAuth.getMSAuthLink(state);
          if (url != null) {
            SystemUtils.setClipboard(url.toString());
            status = "&aLogin link copied!&r";
            cause = null;
            dotCount = 0;
          } else {
            status = "&cFailed to get login link.&r";
            cause = "&cPlease try again.&r";
            dotCount = 0;
          }
        }
        break;
        case 2: {
          mc.displayGuiScreen(previousScreen);
          break;
        }
      }
    }
  }
}