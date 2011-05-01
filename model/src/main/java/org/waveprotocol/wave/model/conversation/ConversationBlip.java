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

package org.waveprotocol.wave.model.conversation;

import org.waveprotocol.wave.model.document.Document;
import org.waveprotocol.wave.model.wave.Blip;
import org.waveprotocol.wave.model.wave.ParticipantId;

import java.util.Set;

/**
 * A conversational element, with content, contributors and other metadata.
 *
 * A blip may be logically deleted, but remain as a parent to non-inline reply
 * threads. A deleted blip is usable only as an accessor to its reply threads,
 * to check if it is deleted, and to attempt deletion again. If those reply
 * threads are subsequently removed then the blip is also removed.
 *
 * TODO(anorth): add setLastModifiedTime, addContributor, when metadata
 * is no longer implicit.
 *
 * @author anorth@google.com (Alex North)
 */
public interface ConversationBlip {
  /**
   * An value-type comprising an inline reply thread with its location. This
   * class is designed to be subclassed to provide a concrete thread type.
   *
   * We can't use a simple Pair for this due to restrictions in Java's generic
   * type matching.
   */
  class InlineReplyThread<T extends ConversationThread> {
    private final T thread;
    private final int location;

    public static <T extends ConversationThread> InlineReplyThread<T> of(T thread, int location) {
      return new InlineReplyThread<T>(thread, location);
    }

    public InlineReplyThread(T thread, int location) {
      this.thread = thread;
      this.location = location;
    }

    // Non-final so it may be overridden.
    public T getThread() {
      return thread;
    }

    public final int getLocation() {
      return location;
    }

    @Override
    public String toString() {
      return "InlineReplyThread(" + thread + " at " + location + ")";
    }

    @Override
    public final boolean equals(Object o) {
      if (o == this) {
        return true;
      }
      if (o == null) {
        return false;
      }
      if (o instanceof InlineReplyThread<?>) {
        InlineReplyThread<?> other = (InlineReplyThread<?>) o;
        return other.thread == thread && other.location == location;
      }
      return false;
    }

    @Override
    public final int hashCode() {
      return 37 * getThread().hashCode() + new Integer(location).hashCode();
    }
  }

  /**
   * Gets the conversation to which this blip belongs.
   */
  Conversation getConversation();

  /**
   * Gets the thread to which this blip belongs.
   */
  ConversationThread getThread();

  /**
   * Gets the reply thread with the given id.
   * @return null if no such thread.
   */
  ConversationThread getReplyThread(String id);

  /**
   * Gets the non-inline replies to this blip.
   *
   * The order of threads is consistent between multiple calls to this method.
   */
  Iterable<? extends ConversationThread> getReplyThreads();

  /**
   * Gets the inline replies to this thread, with their locations in the blip
   * document. The replies are presented in increasing location order (any
   * with invalid locations are last).
   *
   * Note that the reply locations are only valid for immediate use and must not
   * be stored.
   */
  Iterable<? extends InlineReplyThread<? extends ConversationThread>> getInlineReplyThreads();

  /**
   * Gets all reply threads to this blip, in the order defined by history of
   * appends.
   */
  Iterable<? extends ConversationThread> getAllReplyThreads();

  /**
   * Creates a new reply thread to this blip, after any existing replies.
   */
  ConversationThread appendReplyThread();

  /**
   * Creates a new inline reply thread to this blip.
   *
   * @param location location within the blip content at which to anchor
   */
  ConversationThread appendInlineReplyThread(int location);

  /**
   * Gets the content of this blip.
   */
  Document getContent();

  /**
   * Gets the participant id of the author of this blip.
   */
  ParticipantId getAuthorId();

  /**
   * Gets the set of contributors to the blip (this may include the author).
   */
  Set<ParticipantId> getContributorIds();

  /**
   * Gets the last modification timestamp of this blip.
   */
  long getLastModifiedTime();

  /**
   * Gets the last modification version of this blip.
   *
   * TODO(anorth,user): move conversation/blip versioning to an external
   * module, like read/unread.
   */
  long getLastModifiedVersion();

  /**
   * Clears this blip's content document and deletes all reply threads. The blip
   * is removed from the conversation and is no longer usable.
   */
  void delete();

  /**
   * Gets an id for this blip. Blip ids are unique in the scope of the
   * conversation.
   */
  String getId();

  /**
   * Checks whether this blip is the first blip of the root thread of the conversation
   * @return true if this is the first blip of the conversation.
   */
  boolean isRoot();

  //
  // Migration hacks
  //
  Blip hackGetRaw();
}
