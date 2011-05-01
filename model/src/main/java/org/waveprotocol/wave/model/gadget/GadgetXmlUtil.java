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

package org.waveprotocol.wave.model.gadget;

import static org.waveprotocol.wave.model.gadget.GadgetConstants.AUTHOR_ATTRIBUTE;
import static org.waveprotocol.wave.model.gadget.GadgetConstants.CATEGORY_TAGNAME;
import static org.waveprotocol.wave.model.gadget.GadgetConstants.TAGNAME;
import static org.waveprotocol.wave.model.gadget.GadgetConstants.KEY_ATTRIBUTE;
import static org.waveprotocol.wave.model.gadget.GadgetConstants.PREFS_ATTRIBUTE;
import static org.waveprotocol.wave.model.gadget.GadgetConstants.STATE_ATTRIBUTE;
import static org.waveprotocol.wave.model.gadget.GadgetConstants.TITLE_ATTRIBUTE;
import static org.waveprotocol.wave.model.gadget.GadgetConstants.URL_ATTRIBUTE;

import org.waveprotocol.wave.model.document.util.XmlStringBuilder;

/**
 * Static methods to produce Wave Gadget XML elements.
 *
 */
public final class GadgetXmlUtil {

  private GadgetXmlUtil() {} // Non-instantiable.

  /**
   * Returns initialized XML string builder for the gadget.
   *
   * @param url that points to the XML definition of this gadget.
   * @return content XML string for the gadget.
   */
  public static XmlStringBuilder constructXml(String url, String author) {
    return constructXml(url, "", author);
  }

  /**
   * Returns initialized XML string builder for the gadget with initial prefs.
   *
   * @param url that points to the XML definition of this gadget.
   * @param prefs initial gadget preferences as escaped JSON string.
   * @return content XML string for the gadget.
   */
  public static XmlStringBuilder constructXml(String url, String prefs, String author) {
    return constructXml(url, prefs, author, null);
  }

  /**
   * Returns initialized XML string builder for the gadget with initial prefs.
   *
   * @param url that points to the XML definition of this gadget.
   * @param prefs initial gadget preferences as escaped JSON string.
   * @param categories array of category names for the gadget (e.g. ["game",
   *        "chess"]).
   * @return content XML string for the gadget.
   */
  public static XmlStringBuilder constructXml(String url, String prefs, String author,
      String[] categories) {
    XmlStringBuilder builder = XmlStringBuilder.createEmpty();
    if (categories != null) {
      for (int i = 0; i < categories.length; ++i) {
        builder.append(
            XmlStringBuilder.createText("").wrap(
                CATEGORY_TAGNAME, KEY_ATTRIBUTE, categories[i]));
      }
    }
    builder.wrap(
        TAGNAME,
        URL_ATTRIBUTE, url,
        TITLE_ATTRIBUTE, "",
        PREFS_ATTRIBUTE, prefs != null ? prefs : "",
        STATE_ATTRIBUTE, "",
        // TODO(user): Implement finer author management for gadgets.
        AUTHOR_ATTRIBUTE, author);
    return builder;
  }
}
