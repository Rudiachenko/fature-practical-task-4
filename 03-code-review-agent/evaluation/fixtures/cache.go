package cache

import (
	"encoding/json"
	"os"
	"time"
)

// Entry is a cached value with its expiry time.
type Entry struct {
	Value     string
	ExpiresAt time.Time
}

// Cache is an in-memory key/value store that can be persisted to disk.
type Cache struct {
	entries map[string]Entry
	path    string
}

func New(path string) *Cache {
	return &Cache{entries: map[string]Entry{}, path: path}
}

func (c *Cache) Set(key, value string, ttl time.Duration) {
	c.entries[key] = Entry{Value: value, ExpiresAt: time.Now().Add(ttl)}
}

func (c *Cache) Get(key string) (string, bool) {
	e, ok := c.entries[key]
	if !ok || time.Now().After(e.ExpiresAt) {
		return "", false
	}
	return e.Value, true
}

func (c *Cache) Save() {
	data, _ := json.Marshal(c.entries)
	os.WriteFile(c.path, data, 0644)
}
