/*
 * Copyright (C) 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.endpoint.lg.panoviewer;

import com.endpoint.lg.support.window.WindowInstanceIdentity;
import com.endpoint.lg.support.window.ManagedWindow;
import com.endpoint.lg.support.window.WindowIdentity;

import interactivespaces.activity.impl.web.BaseRoutableRosWebActivity;
import interactivespaces.service.web.server.HttpDynamicRequestHandler;
import interactivespaces.service.web.server.HttpRequest;
import interactivespaces.service.web.server.HttpResponse;
import interactivespaces.service.web.server.WebServer;
import interactivespaces.util.data.json.JsonBuilder;
import interactivespaces.util.data.json.JsonNavigator;
import interactivespaces.util.data.json.StandardJsonBuilder;
import interactivespaces.util.data.json.StandardJsonMapper;
import interactivespaces.util.data.json.StandardJsonNavigator;
import interactivespaces.util.web.HttpClientHttpContentCopier;

import com.endpoint.lg.support.evdev.InputAbsState;
import com.endpoint.lg.support.evdev.InputEventCodes;
import com.endpoint.lg.support.message.MessageWrapper;
import com.endpoint.lg.support.message.panoviewer.MessageTypesPanoviewer;
import com.endpoint.lg.support.message.RosMessageHandler;
import com.endpoint.lg.support.message.RosMessageHandlers;
import com.endpoint.lg.support.message.WebsocketMessageHandler;
import com.endpoint.lg.support.message.WebsocketMessageHandlers;
import com.endpoint.lg.support.web.WebConfigHandler;

import com.endpoint.lg.support.message.Scene;
import com.endpoint.lg.support.message.Window;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.HashMap;

/**
 * A panoramic image viewer activity, copied from Matt's streetview pano
 * activity
 * XXX One day the stuff copied from Streetview probably ought to be put in a
 * library somewhere
 *
 * @author Josh Tolley <josh@endpoint.com>
 */
public class PanoViewerActivity extends BaseRoutableRosWebActivity {

  /**
   * The dynamic configuration handler will catch requests for this file.
   */
  public static final String CONFIG_HANDLER_PATH = "is.config.js";

  /**
   * The dynamic configuration handler will catch requests for this file.
   */
  public static final String PROXY_HANDLER_PATH = "proxy";

  /**
   * Coefficient of input event value to POV translation.
   */
  public static final double INPUT_SENSITIVITY = 0.0032;

  /**
   * How much "momentum" on a controller is needed to move forward or backward.
   */
  public static final int INPUT_MOVEMENT_COUNT = 10;

  /**
   * Controller forward/backward axes must exceed this value for movement (after
   * sensitivity).
   */
  public static final double INPUT_MOVEMENT_THRESHOLD = 1.0;

  /**
   * After axial movement, wait this many milliseconds before moving again.
   */
  public static final int INPUT_MOVEMENT_COOLDOWN = 250;

  private WebsocketMessageHandlers wsHandlers;
  private RosMessageHandlers rosHandlers;
  private Object lastMsg;
  private ManagedWindow window;
  private double lastSpnavMsg;
  private HttpClientHttpContentCopier copier;

  /**
   * Sends initialization packet when a new connection arrives
   */
  @Override
  public void onNewWebSocketConnection(String connectionId) {
    if (lastMsg != null) {
      sendWebSocketJson(connectionId, lastMsg);
    }
  }

  /**
   * Sends incoming Ros messages to the Ros message handlers.
   */
  @Override
  public void onNewInputJson(String channel, Map<String, Object> message) {
    rosHandlers.handleMessage(channel, message);
  }

  /**
   * Registers a handler for forwarding messages from Ros to websockets.
   *
   * @param handlers
   *          the Ros handler registry
   * @param type
   *          the message type/channel
   */
  private void relayRosToWebsocket(RosMessageHandlers handlers, final String channel) {
    handlers.registerHandler(channel, new RosMessageHandler() {
      public void handleMessage(JsonNavigator json) {
        // CHANGEPANO messages from the touchscreen need to be un-nested
        if (json.containsProperty("type") && json.getString("type").equals(MessageTypesPanoviewer.MESSAGE_TYPE_CHANGEPANO)) {
          json.down("data");
        }
        JsonBuilder message = MessageWrapper.newTypedMessage(channel, json.getCurrentItem());

        sendAllWebSocketJsonBuilder(message);
      }
    });
  }

  /**
   * Registers message relays and sets up window management.
   */
  @Override
  public void onActivitySetup() {
    rosHandlers = new RosMessageHandlers(getLog());

    relayRosToWebsocket(rosHandlers, MessageTypesPanoviewer.MESSAGE_TYPE_VIEWSYNC);

    WindowIdentity windowId = new WindowInstanceIdentity(getUuid());

    window = new ManagedWindow(this, windowId);
    addManagedResource(window);

    copier = new HttpClientHttpContentCopier();
    addManagedResource(copier);
  }

  /**
   * Starts the web configuration handler.
   */
  @Override
  public void onActivityStartup() {
    lastMsg = null;
    lastSpnavMsg = 0;
    WebServer webserver = getWebServer();
    WebConfigHandler configHandler = new WebConfigHandler(getConfiguration());
    webserver.addDynamicContentHandler(CONFIG_HANDLER_PATH, false, configHandler);
    webserver.addDynamicContentHandler(PROXY_HANDLER_PATH, false, new ProxyHandler());
  }

  @Override
  public void onActivityPostStartup() {
      // Trying to make the window not show up until after we've actually activated
    getLog().info("Trying to hide pano viewer windows");
    window.setVisible(true);
    window.setVisible(false);
    getLog().info("Done trying to hide pano viewer windows");
  }

  /**
   * Shows the window when the activity is activated.
   */
  @Override
  public void onActivityActivate() {
    window.setVisible(true);
  }

  /**
   * Hides the window when the activity is deactivated.
   */
  @Override
  public void onActivityDeactivate() {
    window.setVisible(false);
  }

  /**
   * Applies updates to the window configuration.
   */
  @Override
  public void onActivityConfiguration(Map<String, Object> update) {
    if (window != null)
      window.update();
  }

  private class ProxyHandler implements HttpDynamicRequestHandler {
    public void handle(HttpRequest request, HttpResponse response) {
      String url;
      int bytes;
      byte[] buffer = new byte[1024*1024];
      File tempFile;
      Map<String, String> params = request.getUriQueryParameters();

      try {
        tempFile = File.createTempFile("pano-proxy-", ".data");
        url = params.get("query");
        request.getLog().info("URL: " + url);
        response.setResponseCode(200);
        copier.copy(url, tempFile);
        FileInputStream fis = new FileInputStream(tempFile);
        while (true) {
          bytes = fis.read(buffer);
          if (bytes == -1) {
            break;
          }
          else {
            response.getOutputStream().write(buffer, 0, bytes);
          }
        }
        tempFile.delete();
      }
      catch (Exception e) {
        getLog().error(e);
      }
    }
  }
}
