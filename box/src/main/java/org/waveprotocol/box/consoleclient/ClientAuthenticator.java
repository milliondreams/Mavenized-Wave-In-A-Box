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

package org.waveprotocol.box.consoleclient;

import com.google.common.base.Preconditions;

import org.waveprotocol.wave.util.escapers.PercentEscaper;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;

/**
 * Client authenticator. Used by the client to connect to the authentication servlet and returns
 * the server-provided authentication cookie.
 *
 * @author josephg@gmail.com (Joseph Gentle)
 */
public class ClientAuthenticator {
  private final URL endpoint;

  /**
   * Create a new user authenticator at a specified URL endpoint.
   *
   * @param endpoint The server endpoint, eg "http://localhost:9898" +
   *        SessionManager.SIGN_IN_URL
   * @throws MalformedURLException The provided URL is invalid.
   */
  public ClientAuthenticator(String endpoint) throws MalformedURLException {
    this.endpoint = new URL(endpoint);
    Preconditions.checkArgument(
        this.endpoint.getProtocol().equals("http") || this.endpoint.getProtocol().equals("https"));
  }

  /**
   * Authenticate a user. Returns the user's authentication cookie if authentication succeeds or
   * null if authentication fails.
   *
   * Only password based authentication is currently supported.
   *
   * @param address The user's address. Should be fully qualified (user@example.com)
   * @param password The user's password.
   * @return The authentication token cookie, or null if authentication fails for any reason.
   * @throws IOException An error occurred contacting the server.
   */
  public HttpCookie authenticate(String address, char[] password) throws IOException {
    HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
    try {
      connection.setRequestMethod("POST");
      connection.setDoOutput(true);
      connection.setAllowUserInteraction(false);
      connection.setInstanceFollowRedirects(false);

      PercentEscaper escaper = new PercentEscaper(PercentEscaper.SAFECHARS_URLENCODER, true);
      String data = "address=" + escaper.escape(address) + "&" + "password="
          + escaper.escape(new String(password));

      OutputStream out = connection.getOutputStream();
      Writer writer = new OutputStreamWriter(out, "UTF-8");
      try {
        writer.write(data);
      } finally {
        writer.close();
      }

      String cookieHeader = connection.getHeaderField("Set-Cookie");
      List<HttpCookie> cookies;
      if (cookieHeader != null) {
        cookies = HttpCookie.parse(cookieHeader);
        if (cookies.size() == 0) {
          return null;
        }
        return cookies.get(0);
      }
      return null;
    } finally {
      connection.disconnect();
    }
  }
}
