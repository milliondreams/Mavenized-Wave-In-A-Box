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
package org.waveprotocol.wave.model.util;

import java.util.Map;

/**
 * A read-write interface to a map of ints to V.
 *
 * @param <V> type of values in the map
 */
public interface IntMap<V> extends ReadableIntMap<V> {
  /**
   * A function that accepts a key and the corresponding value from the map.
   * It should return true to keep the item in the map, false to remove.
   */
  public interface EntryFilter<V> {
    public boolean apply(int key, V value);
  }

  /**
   * Sets the value associated with key to value.
   * If key already has a value, replaces it.
   */
  void put(int key, V value);

  /**
   * Removes the value associated with key.  Does nothing if there is none.
   */
  void remove(int key);

  /**
   * Equivalent to calling put(key, value) for every key-value pair in
   * pairsToAdd.
   *
   * TODO(ohler): Remove this requirement.
   * Any data structure making use of a CollectionsFactory must only pass
   * instances of ReadableIntMap created by that factory as the pairsToAdd
   * argument.  That is, if the Factory only creates IntMaps of a certain
   * type, you can rely on the fact that this method will only be called with
   * intMaps of that type.
   */
  void putAll(ReadableIntMap<V> pairsToAdd);

  /**
   * The equivalent of calling put(key, value) for every key-value pair in
   * sourceMap.
   */
  void putAll(Map<Integer, V> sourceMap);

  /**
   * Removes all key-value pairs from this map.
   */
  void clear();

  /**
   * Call the filter for each key-value pair in the map, in undefined
   * order.  If the filter returns false, the pair is removed from the map;
   * if it returns true, it remains.
   */
  void filter(EntryFilter<V> filter);
}

