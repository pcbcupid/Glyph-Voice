import type { Conversation, Summary } from '../core/types';

export class HistoryStore {
  private opening?: Promise<IDBDatabase>;
  private deleted = new Set<string>();
  private open() {
    return (this.opening ??= new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open('glyph-voice-web', 1);
      request.onupgradeneeded = () => {
        request.result.createObjectStore('conversations', { keyPath: 'id' });
        request.result.createObjectStore('summaries', { keyPath: 'id' });
      };
      request.onsuccess = () => {
        request.result.onversionchange = () => request.result.close();
        resolve(request.result);
      };
      request.onerror = () => {
        this.opening = undefined;
        reject(new Error('Browser storage unavailable.'));
      };
      request.onblocked = () => reject(new Error('Close other Glyph tabs to open history.'));
    }));
  }
  private async mutate(store: string, perform: (table: IDBObjectStore) => void) {
    const db = await this.open();
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(store, 'readwrite');
      tx.oncomplete = () => resolve();
      tx.onabort = tx.onerror = () =>
        reject(new Error('Could not save history. Check browser storage.'));
      perform(tx.objectStore(store));
    });
  }
  async save(value: Conversation) {
    if (!value.text || this.deleted.has(value.id)) return;
    await this.mutate('conversations', (table) => {
      if (!this.deleted.has(value.id)) table.put(value);
    });
  }
  async list<T extends Conversation | Summary>(table: 'conversations' | 'summaries'): Promise<T[]> {
    const db = await this.open();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(table),
        request = tx.objectStore(table).getAll();
      request.onsuccess = () =>
        resolve((request.result as T[]).sort((a, b) => b.created - a.created));
      request.onerror = () => reject(new Error('Could not read history.'));
    });
  }
  async delete(id: string, summary: boolean) {
    if (!summary) this.deleted.add(id);
    try {
      await this.mutate(summary ? 'summaries' : 'conversations', (table) => table.delete(id));
    } catch (e) {
      this.deleted.delete(id);
      throw e;
    }
  }
  async recover() {
    const entries = await this.list<Conversation>('conversations');
    for (const row of entries)
      if (row.status === 'recording') await this.save({ ...row, status: 'interrupted' });
  }
}
