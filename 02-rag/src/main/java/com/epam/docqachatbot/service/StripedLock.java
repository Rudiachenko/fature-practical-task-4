package com.epam.docqachatbot.service;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounded per-key mutual exclusion: a fixed-size array of locks, one of which is selected
 * deterministically by {@code key.hashCode()}. Never allocates a lock per key and never falls
 * back to a single global lock; two different keys may occasionally share a stripe, which only
 * costs unnecessary (never incorrect) serialization.
 */
final class StripedLock {

  private final ReentrantLock[] locks;

  StripedLock(int stripeCount) {
    locks = new ReentrantLock[stripeCount];
    for (int index = 0; index < stripeCount; index++) {
      locks[index] = new ReentrantLock();
    }
  }

  void withLock(String key, Runnable action) {
    ReentrantLock lock = locks[Math.floorMod(key.hashCode(), locks.length)];
    lock.lock();
    try {
      action.run();
    } finally {
      lock.unlock();
    }
  }
}
