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

package org.waveprotocol.box.consoleclient;

import com.google.common.collect.Maps;

import org.waveprotocol.wave.model.id.WaveId;
import org.waveprotocol.wave.model.id.WaveletId;
import org.waveprotocol.wave.model.id.WaveletName;
import org.waveprotocol.wave.model.version.HashedVersion;
import org.waveprotocol.wave.model.version.HashedVersionFactory;
import org.waveprotocol.wave.model.wave.data.ObservableWaveletData;
import org.waveprotocol.wave.model.wave.data.WaveViewData;
import org.waveprotocol.wave.model.wave.data.WaveletData;
import org.waveprotocol.wave.model.wave.data.impl.WaveViewDataImpl;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A client's view of a wave, with the current wavelet versions.
 *
 *
 */
public class ClientWaveView {
  /** Wave this is the view of. */
  private final WaveViewData data;

  /** Last known version of each wavelet. */
  private final Map<WaveletId, HashedVersion> currentVersions;

  /** Factory for creating hashed versions. */
  private final HashedVersionFactory hashedVersionFactory;

  /**
   * Read Lock for changes to wavelet versions.
   * TODO: move to ClientWaveletView when it exists
   */
  private final Lock versionReadLock;

  /**
   * Write lock for changes to wavelet versions.
   * TODO: move to ClientWaveletView when it exists
   */
  private final Lock versionWriteLock;

  /**
   * Condition variable for signalling version changes to wavelets
   * TODO: move to ClientWaveletView when it exists
   */
  private final Condition versionChangeCondition;

  /**
   * @param hashedVersionFactory for generating hashed versions
   * @param waveId of the wave
   */
  public ClientWaveView(HashedVersionFactory hashedVersionFactory, WaveId waveId) {
    this.hashedVersionFactory = hashedVersionFactory;
    this.data = WaveViewDataImpl.create(waveId);
    this.currentVersions = Maps.newHashMap();
    ReadWriteLock versionReadWriteLock = new ReentrantReadWriteLock();
    versionReadLock = versionReadWriteLock.readLock();
    versionWriteLock = versionReadWriteLock.writeLock();
    versionChangeCondition = versionWriteLock.newCondition();
  }

  /**
   * Get the unique identifier of the wave in view.
   *
   * @return the unique identifier of the wave.
   */
  public WaveId getWaveId() {
    return data.getWaveId();
  }

  /**
   * Gets the wavelets in this wave view. The order of iteration is unspecified.
   *
   * @return wavelets in this wave view.
   */
  public Iterable<? extends WaveletData> getWavelets() {
    return data.getWavelets();
  }

  /**
   * Gets the last known version for a wavelet. Returns version 0 for unknown
   * wavelets.
   *
   * @param waveletId of the wavelet
   * @return last known version for wavelet
   */
  public HashedVersion getWaveletVersion(WaveletId waveletId) {
    versionReadLock.lock();
    try {
      HashedVersion version = currentVersions.get(waveletId);
      if (version == null) {
        return hashedVersionFactory.createVersionZero(WaveletName.of(data.getWaveId(), waveletId));
      } else {
        return version;
      }
    } finally {
      versionReadLock.unlock();
    }
  }

  /**
   * Sets the last known version for a wavelet.
   *
   * @param waveletId of the wavelet
   * @param version of the wavelet
   */
  public void setWaveletVersion(WaveletId waveletId, HashedVersion version) {
    versionWriteLock.lock();
    try {
      currentVersions.put(waveletId, version);
      versionChangeCondition.signalAll();
    } finally {
      versionWriteLock.unlock();
    }
  }

  /**
   * Block and wait for a wavelet to reach a given version.  If this wave has no record of the
   * wavelet, wait until it has been created.
   *
   * @param waveletId of the wavelet
   * @param version of the wavelet
   * @param timeout to block for
   * @param unit of timeunit
   * @return false if the waiting time elapsed, true otherwise
   */
  public boolean awaitWaveletVersion(WaveletId waveletId, long version, long timeout,
      TimeUnit unit) {
    versionWriteLock.lock();
    long timeoutNanos = System.nanoTime() + TimeUnit.NANOSECONDS.convert(timeout, unit);
    try {
      while ((getWavelet(waveletId) == null)
          || (getWaveletVersion(waveletId).getVersion() < version)) {
        if (timeoutNanos < System.nanoTime()) {
          return false;
        }
        versionChangeCondition.awaitNanos(timeoutNanos - System.nanoTime());
      }
    } catch (InterruptedException e) {
      throw new IllegalStateException(e);
    } finally {
      versionWriteLock.unlock();
    }
    return true;
  }

  /**
   * Get a wavelet from the view by id.
   *
   * @return the requested wavelet, or null if it is not in view.
   */
  public ObservableWaveletData getWavelet(WaveletId waveletId) {
    return data.getWavelet(waveletId);
  }

  /**
   * Adds a wavelet to this view.
   * @param wavelet the wavelet to add
   * @param version the version of the wavelet
   */
  public void addWavelet(ObservableWaveletData wavelet, HashedVersion version) {
    data.addWavelet(wavelet);
    currentVersions.put(wavelet.getWaveletId(), version);
  }

  /**
   * Removes a wavelet and its current hashed version from the wave view.
   *
   * @param waveletId of wavelet to remove
   */
  public void removeWavelet(WaveletId waveletId) {
    data.removeWavelet(waveletId);
    currentVersions.remove(waveletId);
  }
}
