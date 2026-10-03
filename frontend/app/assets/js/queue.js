import { programController } from './programController.js';
import * as enums from './enums.js';
import { storageHandler } from './storageHandler.js';
import { resumableOperationRegistry, OperationState } from './resumableOperation.js';

class Queue {
  constructor() { this._items = []; }
  enqueue(item) { this._items.push(item); }
  dequeue() { return this._items.shift(); }
  get size() { return this._items.length; }
}

/**
 * What an author-list or date-based bulk action does, as a category, or null
 * when the action name is absent and the run's mode and target type decide.
 */
function categoryOfNamedAction(action) {
  switch (action) {
    case enums.DateBulkAction.ENGELLE:
      return enums.TaskCategory.BLOCKING;
    case enums.DateBulkAction.SESSIZE_AL:
      return enums.TaskCategory.MUTING;
    case enums.DateBulkAction.ENGEL_KALDIR:
      return enums.TaskCategory.UNBLOCKING;
    case enums.DateBulkAction.SESSIZDEN_CIKAR:
      return enums.TaskCategory.UNMUTING;
    // The unblock or unmute in these two is the way to the follow, not the point.
    case enums.DateBulkAction.TAKIP_ET:
    case enums.DateBulkAction.ENGEL_KALDIR_VE_TAKIP_ET:
    case enums.DateBulkAction.SESSIZDEN_CIKAR_VE_TAKIP_ET:
      return enums.TaskCategory.FOLLOWING;
    case enums.DateBulkAction.TAKIPTEN_CIKAR:
      return enums.TaskCategory.UNFOLLOWING;
    default:
      return null;
  }
}

/**
 * What a task does, for the category column.
 *
 * Read from the source alone this said "Engelleme" for every run that acts on
 * an audience -- "yazarı takip et", "favlayanları sessize al" and "engellemeyi
 * bırak" all queued as blocks -- because SINGLE, FAV, FOLLOW, LIST and TITLE
 * each carry every relation. The source says whom; the mode, the target type
 * and the author list's or date-based run's action name say what.
 *
 * A null target type is a restricting run whose block-or-mute choice is the
 * user's enableMute setting, read when the run starts. Pass it in `enableMute`;
 * the background stores the result on the task as metadata.taskCategory at
 * enqueue, because the notification page has no copy of the setting.
 *
 * Mirrored by OperationLabel.kind in android/ops/runtime/.../OperationLabel.kt.
 */
export function getTaskCategory(banSource, details = {}) {
  const { banMode, targetType, listAction, bulkAction, enableMute = false } = details;
  switch (banSource) {
    case enums.BanSource.MIGRATE_BLOCKED_TO_MUTED:
    case enums.BanSource.BLOCK_MUTED_USERS:
      return enums.TaskCategory.MIGRATION;
    // Blocks the titles of accounts already blocked or muted: nothing moves
    // from one list to another, so it is a block, not "Taşıma".
    case enums.BanSource.BLOCKED_MUTED_TITLES:
      return banMode === enums.BanMode.UNDOBAN
        ? enums.TaskCategory.UNBLOCKING
        : enums.TaskCategory.BLOCKING;
    case enums.BanSource.REFRESH_MUTED_LIST:
    case enums.BanSource.REFRESH_BLOCKED_LIST:
    case enums.BanSource.REFRESH_FOLLOWED_LIST:
      return enums.TaskCategory.REFRESH;
    case enums.BanSource.UNDOBANALL:
      return enums.TaskCategory.UNBLOCKING;
    case enums.BanSource.UNMUTEALL:
      return enums.TaskCategory.UNMUTING;
    case enums.BanSource.DATE_BASED_BULK:
      return categoryOfNamedAction(bulkAction) || enums.TaskCategory.BLOCKING;
    case enums.BanSource.LIST: {
      const named = categoryOfNamedAction(listAction);
      if (named) return named;
      break;
    }
    // Offered only as a follow; an older task may not carry the target type.
    case enums.BanSource.FOLLOWEES:
      return banMode === enums.BanMode.UNDOBAN
        ? enums.TaskCategory.UNFOLLOWING
        : enums.TaskCategory.FOLLOWING;
  }

  const undo = banMode === enums.BanMode.UNDOBAN;
  const relation = targetType || (enableMute ? enums.TargetType.MUTE : enums.TargetType.USER);
  if (relation === enums.TargetType.FOLLOW) {
    return undo ? enums.TaskCategory.UNFOLLOWING : enums.TaskCategory.FOLLOWING;
  }
  if (relation === enums.TargetType.MUTE) {
    return undo ? enums.TaskCategory.UNMUTING : enums.TaskCategory.MUTING;
  }
  return undo ? enums.TaskCategory.UNBLOCKING : enums.TaskCategory.BLOCKING;
}

/**
 * The category of a queued or finished task, from what it carries.
 *
 * Prefers the category the background stored at enqueue, which saw the
 * enableMute setting; recomputes for a task persisted before it was stored.
 */
export function taskCategoryOf(banSource, metadata = {}, banMode = metadata.banMode) {
  if (metadata.taskCategory) return metadata.taskCategory;
  return getTaskCategory(banSource, {
    banMode,
    targetType: (metadata.targetTypes || [])[0] || null,
    listAction: metadata.listAction || null,
    bulkAction: metadata.bulkAction || null,
  });
}

function getTaskComplexity(banSource) {
  switch (banSource) {
    case enums.BanSource.SINGLE:
      return enums.TaskComplexity.SIMPLE;
    case enums.BanSource.FAV:
    case enums.BanSource.FOLLOW:
    case enums.BanSource.LIST:
      return enums.TaskComplexity.MODERATE;
    case enums.BanSource.TITLE:
    case enums.BanSource.MIGRATE_BLOCKED_TO_MUTED:
    case enums.BanSource.BLOCK_MUTED_USERS:
    case enums.BanSource.BLOCKED_MUTED_TITLES:
      return enums.TaskComplexity.COMPLEX;
    case enums.BanSource.UNDOBANALL:
    case enums.BanSource.REFRESH_MUTED_LIST:
    case enums.BanSource.REFRESH_BLOCKED_LIST:
    case enums.BanSource.REFRESH_FOLLOWED_LIST:
      return enums.TaskComplexity.HEAVY;
    default:
      return enums.TaskComplexity.MODERATE;
  }
}

function getTaskPriority(banSource) {
  switch (banSource) {
    case enums.BanSource.REFRESH_MUTED_LIST:
    case enums.BanSource.REFRESH_BLOCKED_LIST:
    case enums.BanSource.REFRESH_FOLLOWED_LIST:
      return enums.TaskPriority.LOW;
    case enums.BanSource.SINGLE:
      return enums.TaskPriority.HIGH;
    default:
      return enums.TaskPriority.NORMAL;
  }
}

/**
 * The bracketed detail after an action name.
 *
 * The nick comes first because it is what tells two otherwise identical rows
 * apart -- three queued "Favori Edenleri Engelle" entries read as the same one
 * repeated until each says whose entry it is.
 */
function describeTarget(...parts) {
  const kept = parts.filter(Boolean);
  return kept.length ? ` (${kept.join(" - ")})` : "";
}

/**
 * The arguments a task was dispatched with, in the shape the background message
 * listener expects back, so İşlem durumu can re-enqueue it or open its source.
 *
 * Returns null for a task with no single target -- a bulk or list-sourced run
 * has no one entry/author/title to replay against or navigate to. Callers treat
 * null as "offer neither action", which is also what an item persisted before
 * these fields existed produces.
 */
export function buildRetryParams(action = {}, metadata = {}) {
  const banSource = action.banSource ?? metadata.banSource;
  const banMode = action.banMode ?? metadata.banMode;
  const authorId = metadata.sourceAuthorId || null;
  const titleId = metadata.sourceTitleId || null;
  const entryUrl = metadata.sourceEntry || null;
  const authorName = metadata.sourceAuthor || null;
  const titleName = metadata.sourceTitle || null;

  if (!banSource || (!entryUrl && !authorName && !titleName)) return null;

  return {
    banSource,
    banMode,
    entryUrl,
    authorName,
    authorId,
    titleName,
    titleId,
    targetType: (metadata.targetTypes || [])[0] || null,
    clickSource: metadata.clickSource || null,
    timeSpecifier: metadata.timeFilter || null,
    action: metadata.listAction || null
  };
}

/** The verb a description uses for each category. */
const CATEGORY_VERBS = {
  [enums.TaskCategory.BLOCKING]: "Engelle",
  [enums.TaskCategory.UNBLOCKING]: "Engel Kaldır",
  [enums.TaskCategory.MUTING]: "Sessize Al",
  [enums.TaskCategory.UNMUTING]: "Sessizden Çıkar",
  [enums.TaskCategory.FOLLOWING]: "Takip Et",
  [enums.TaskCategory.UNFOLLOWING]: "Takipten Çıkar",
};

export function generateUnifiedDescription(banSource, metadata = {}) {
  const { targetTypes = [], sourceEntry, sourceAuthor, sourceTitle, sourceList, timeFilter, listAction, bulkAction } = metadata;
  let baseDescription = "";
  // From the same category the column shows, so the two cannot disagree: this
  // used to be "Engelle" unless the mode was UNDOBAN, whatever the target type.
  const operationType = CATEGORY_VERBS[taskCategoryOf(banSource, metadata)] || "Engelle";
  const listActionLabels = {
    [enums.DateBulkAction.ENGELLE]: "Engelle",
    [enums.DateBulkAction.SESSIZE_AL]: "Sessize Al",
    [enums.DateBulkAction.ENGEL_KALDIR]: "Engel Kaldır",
    [enums.DateBulkAction.SESSIZDEN_CIKAR]: "Sessizden Çıkar",
    [enums.DateBulkAction.TAKIP_ET]: "Takip Et",
    [enums.DateBulkAction.ENGEL_KALDIR_VE_TAKIP_ET]: "Engel Kaldır ve Takip Et",
    [enums.DateBulkAction.SESSIZDEN_CIKAR_VE_TAKIP_ET]: "Sessizden Çıkar ve Takip Et",
    [enums.DateBulkAction.TAKIPTEN_CIKAR]: "Takipten Çıkar"
  };
  // Only what the verb does not already say. Every type used to be named, and
  // anything not a user or a title was called "Sessiz" -- a follow included.
  const targetTypeNames = targetTypes.includes(enums.TargetType.TITLE) ? "Başlık" : null;

  switch (banSource) {
    case enums.BanSource.SINGLE:
      baseDescription = `Tek Kullanıcı ${operationType}`;
      baseDescription += describeTarget(sourceAuthor, targetTypeNames);
      break;
    case enums.BanSource.FAV:
      baseDescription = `Favori Edenleri ${operationType}`;
      // The entry's author, not the favouriters -- it is whose entry was
      // clicked, and "Entry" said only that there had been one.
      baseDescription += describeTarget(sourceAuthor || (sourceEntry ? "Entry" : null));
      break;
    case enums.BanSource.FOLLOW:
      baseDescription = `Takipçileri ${operationType}`;
      baseDescription += describeTarget(sourceAuthor);
      break;
    case enums.BanSource.FOLLOWEES:
      baseDescription = `Takip Ettiklerini ${operationType}`;
      baseDescription += describeTarget(sourceAuthor);
      break;
    case enums.BanSource.LIST:
      baseDescription = listActionLabels[listAction]
        ? `Listeden ${listActionLabels[listAction]}`
        : `Listeden ${operationType}`;
      if (sourceList && sourceList.length > 0) baseDescription += ` (${sourceList.length} kullanıcı)`;
      break;
    case enums.BanSource.TITLE:
      baseDescription = `Başlıktaki Yazarları ${operationType}`;
      if (sourceTitle) baseDescription += ` (${sourceTitle})`;
      if (timeFilter) {
        const timeDesc = timeFilter === enums.TimeSpecifier.LAST_24_H ? "Son 24 saat" : "Tümü";
        baseDescription += ` - ${timeDesc}`;
      }
      break;
    case enums.BanSource.UNDOBANALL:
      baseDescription = "Tüm Engelleri Kaldır";
      break;
    case enums.BanSource.MIGRATE_BLOCKED_TO_MUTED:
      baseDescription = "Engelli Kullanıcıları Sessize al";
      break;
    case enums.BanSource.BLOCK_MUTED_USERS:
      baseDescription = "Sessiz Kullanıcıları Engelle";
      break;
    case enums.BanSource.BLOCKED_MUTED_TITLES:
      baseDescription = "Engelli/Sessiz Başlıkları Engelle";
      break;
    case enums.BanSource.REFRESH_MUTED_LIST:
      baseDescription = "Sessiz Listesi Yenile";
      break;
    case enums.BanSource.REFRESH_BLOCKED_LIST:
      baseDescription = "Engelli Listesi Yenile";
      break;
    case enums.BanSource.REFRESH_FOLLOWED_LIST:
      baseDescription = "Takip Edilenler Listesi Yenile";
      break;
    case enums.BanSource.UNMUTEALL:
      baseDescription = "Tüm Sessizleri Kaldır";
      break;
    case enums.BanSource.DATE_BASED_BULK:
      baseDescription = listActionLabels[bulkAction]
        ? `Tarih Bazlı Toplu İşlem: ${listActionLabels[bulkAction]}`
        : "Tarih Bazlı Toplu İşlem";
      break;
    default:
      baseDescription = `${operationType} İşlemi`;
  }
  return baseDescription;
}

/**
 * What a removed task settles with.
 *
 * A single frozen instance, compared by identity rather than by shape: a task
 * handler is free to resolve with whatever it likes, and a future one returning
 * `{ cancelled: true }` must not be mistaken for a task the reader dropped.
 */
export const CANCELLED_TASK = Object.freeze({ cancelled: true });

/** Whether a settled task was removed from the queue rather than run. */
export function isCancelledTask(result) {
  return result === CANCELLED_TASK;
}

let taskIdCounter = 0;

/** Unique within a session, and stable across a save/restore of the queue. */
function newTaskId() {
  return `task-${Date.now().toString(36)}-${(taskIdCounter++).toString(36)}`;
}

class AutoQueue extends Queue {
  constructor() {
    super();
    this._pendingPromise = false;
    this._currentItem = null;
    this._isInitialized = false;
    this._initializePersistedQueue();
  }

  async _initializePersistedQueue() {
    try {
      const lastResult = await storageHandler.getLastOperationResult();
      
      // If previous operation was STOPPED, clear any stale queue data
      // This prevents stale/invalid items from being restored after user cancels
      if (lastResult && lastResult.result === 'STOPPED') {
        console.log("Queue: Previous operation was STOPPED, clearing stale queue data");
        await storageHandler.saveQueueData([]);
        this._items = [];
        this._isInitialized = true;
        return;
      }
      
      const persistedItems = await storageHandler.getQueueData();
      
      // Handle edge cases: no items, not an array, or empty
      if (!persistedItems || !Array.isArray(persistedItems) || persistedItems.length === 0) {
        console.log("Queue: No persisted queue items found");
        this._items = [];
        this._isInitialized = true;
        return;
      }
      
      // Validate items - remove non-executable ones
      const validItems = [];
      for (const item of persistedItems) {
        // Check if item has required properties for reconstruction
        // Also verify banSource is not null/undefined
        const hasValidAction = item && item.action && 
          typeof item.action.banSource !== 'undefined' && 
          item.action.banSource !== null;
        
        if (hasValidAction) {
          validItems.push(item);
        } else {
          // Log detailed info about what's missing for debugging
          const missingProps = [];
          if (!item) missingProps.push('item is null/undefined');
          if (!item?.action) missingProps.push('item.action is missing');
          if (item?.action && typeof item.action.banSource === 'undefined') missingProps.push('item.action.banSource is undefined');
          if (item?.action && item.action.banSource === null) missingProps.push('item.action.banSource is null');
          
          console.debug(`Queue: Removing invalid item - missing: ${missingProps.join(', ')}`, item);
        }
      }
      
      // If ALL items were invalid, clear the queue to prevent repeated warnings
      if (validItems.length === 0 && persistedItems.length > 0) {
        console.log("Queue: All items were invalid, clearing queue storage");
        await storageHandler.saveQueueData([]);
        this._items = [];
        this._isInitialized = true;
        return;
      }
      
      this._items = validItems;
      const invalidCount = persistedItems.length - validItems.length;
      if (invalidCount > 0) {
        console.log(`Queue: Restored ${this._items.length} valid items from storage (${invalidCount} invalid items removed)`);
      }
      
      // Only auto-start if previous operation completed successfully
      if (this._items.length > 0) {
        const shouldAutoStart = !lastResult || lastResult.result === 'COMPLETED';
        
        if (shouldAutoStart) {
          // Delay to allow other components to initialize
          setTimeout(() => {
            console.log("Queue: Auto-starting queue processing after restoration (previous: COMPLETED or none)");
            this.dequeue();
          }, 1000);
        } else {
          console.log(`Queue: Not auto-starting - previous operation result was: ${lastResult?.result}`);
        }
      }
    } catch (error) {
      console.warn('Queue: Failed to restore queue from storage:', error);
    } finally {
      this._isInitialized = true;
    }
  }

  async _saveQueueState() {
    if (!this._isInitialized) return;
    try {
      // Filter out invalid items before saving (defensive check)
      const validItems = this._items.filter(item => {
        const isValid = item && item.action && 
          typeof item.action.banSource !== 'undefined' && 
          item.action.banSource !== null;
        
        if (!isValid) {
          // Log what's invalid for debugging
          const issue = !item ? 'item is falsy' : 
            !item.action ? 'item.action is falsy' : 
            typeof item.action.banSource === 'undefined' ? 'banSource is undefined' : 
            item.action.banSource === null ? 'banSource is null' : 'unknown';
          console.debug(`Queue: Filtering out invalid item before save: ${issue}`, item);
        }
        
        return isValid;
      });
      
      if (validItems.length !== this._items.length) {
        console.warn(`Queue: Filtering out ${this._items.length - validItems.length} invalid items before saving`);
        this._items = validItems;
      }
      
      // Log what we're about to save (limited info for privacy)
      const saveInfo = validItems.map(item => ({
        hasAction: !!item?.action,
        banSource: item?.action?.banSource,
        hasResolve: !!item?.resolve,
        hasReject: !!item?.reject
      }));
      console.log(`Queue: Saving ${validItems.length} items to storage:`, saveInfo);
      
      const itemsData = validItems.map(item => ({ action: item.action, resolve: undefined, reject: undefined }));
      await storageHandler.saveQueueData(itemsData);
    } catch (error) {
      console.warn('Queue: Failed to save queue state:', error);
    }
  }

  get item() { return this._items; }


  get itemAttributes() {
    const attrs = [];
    
    // First, add the currently running task if there is one
    if (this._pendingPromise && this._currentItem) {
      const item = this._currentItem;
      const action = item.action || {};
      const metadata = action.metadata || {};
      attrs.push({
        banSource: action.banSource,
        banMode: action.banMode,
        creationDateInStr: action.creationDateInStr,
        actionDescription: action.actionDescription || generateUnifiedDescription(action.banSource, { ...metadata, banMode: action.banMode }),
        taskCategory: taskCategoryOf(action.banSource, metadata, action.banMode),
        taskComplexity: getTaskComplexity(action.banSource),
        taskPriority: getTaskPriority(action.banSource),
        sourceEntry: metadata.sourceEntry || null,
        sourceAuthor: metadata.sourceAuthor || null,
        sourceTitle: metadata.sourceTitle || null,
        sourceList: metadata.sourceList || null,
        targetTypes: metadata.targetTypes || [],
        timeFilter: metadata.timeFilter || null,
        // The arguments processHandler was bound to, carried through so İşlem
        // durumu can replay the task or open what it acted on.
        retryParams: buildRetryParams(action, metadata),
        taskId: action.taskId || null,
        taskStatus: enums.TaskStatus.PROCESSING,
        operationNotes: metadata.operationNotes || "",
        requiresUserInteraction: metadata.requiresUserInteraction || false,
        queuePosition: 0,
        totalQueueSize: this._items.length + 1
      });
    }
    
    // Then add all queued items
    for(let i = 0; i < this._items.length; i++) {
      const action = this._items[i].action;
      const metadata = action.metadata || {};
      attrs.push({
        banSource: action.banSource,
        banMode: action.banMode,
        creationDateInStr: action.creationDateInStr,
        actionDescription: action.actionDescription || generateUnifiedDescription(action.banSource, { ...action.metadata, banMode: action.banMode }),
        taskCategory: taskCategoryOf(action.banSource, metadata, action.banMode),
        taskComplexity: getTaskComplexity(action.banSource),
        taskPriority: getTaskPriority(action.banSource),
        sourceEntry: metadata.sourceEntry || null,
        sourceAuthor: metadata.sourceAuthor || null,
        sourceTitle: metadata.sourceTitle || null,
        sourceList: metadata.sourceList || null,
        targetTypes: metadata.targetTypes || [],
        timeFilter: metadata.timeFilter || null,
        // The arguments processHandler was bound to, carried through so İşlem
        // durumu can replay the task or open what it acted on.
        retryParams: buildRetryParams(action, metadata),
        taskId: action.taskId || null,
        taskStatus: enums.TaskStatus.QUEUED,
        operationNotes: metadata.operationNotes || "",
        requiresUserInteraction: metadata.requiresUserInteraction || false,
        queuePosition: i + 1,
        totalQueueSize: this._items.length + (this._pendingPromise ? 1 : 0)
      });
    }
    return attrs;
  }


  /**
   * Drops a waiting task.
   *
   * Only ever a waiting one: the running task is not in `_items`, and stopping
   * a run that has already started is what the stop button is for. A task
   * persisted before ids existed carries none, matches nothing here, and its
   * row simply offers no "kaldır" -- the same way the other row actions appear
   * only when the stored task carries what they need.
   */
  async removeByTaskId(taskId) {
    if (!taskId) return false;
    
    const removed = this._items.filter(item => item?.action?.taskId === taskId);
    if (removed.length === 0) return false;
    
    this._items = this._items.filter(item => item?.action?.taskId !== taskId);
    
    // Whoever called enqueue is still awaiting the promise it handed back.
    // Settling it keeps that await from hanging for the life of the page;
    // items restored from storage carry no resolve and are simply dropped.
    for (const item of removed) {
      try {
        item.resolve?.(CANCELLED_TASK);
      } catch (error) {
        console.debug("Queue: removed item had no settleable promise", error);
      }
    }
    
    await this._saveQueueState();
    return true;
  }


  get isRunning() { return this._pendingPromise; }

  async clear() {
    this._items = [];
    await this._saveQueueState();
  }

  async enqueue(action) {
    return new Promise(async (resolve, reject) => {
      // A stable handle for the row in İşlem durumu. Queue positions shift as
      // tasks finish, so "remove the third one" would race the queue and drop
      // whichever task had moved into that slot. It lives on `action` because
      // that is the only part of an item persistence keeps.
      if (action && !action.taskId) action.taskId = newTaskId();
      super.enqueue({ action, resolve, reject });
      await this._saveQueueState();
      this.dequeue();
    });
  }


  async dequeue() {
    if (this._pendingPromise) {
      console.log("Queue: Skipping dequeue - promise already pending");
      return false;
    }
    
    // Check if programController is truly active (not just has a paused operation)
    // We need to allow dequeue if there's only a paused operation that's about to be cleared
    if (programController && programController.isActive) {
      // Check if the only thing blocking is a paused operation that's about to be cleared
      const hasPausedOp = resumableOperationRegistry.hasPausedOperation();
      if (hasPausedOp) {
        console.log("Queue: Skipping dequeue - paused operation exists (resume or stop it first)");
        return false;
      }
      console.log("Queue: Skipping dequeue - programController is active");
      return false;
    }

    const item = super.dequeue();
    if (!item) {
      console.log("Queue: No item to dequeue");
      return false;
    }

    // Check if the action is actually executable (a function)
    // Items restored from storage will have a plain object as action, not a function
    if (typeof item.action !== 'function') {
      console.warn("Queue: Item action is not executable (likely restored from storage). Skipping item.");
      await this._saveQueueState();
      // Try to process next item
      setTimeout(() => this.dequeue(), 0);
      return false;
    }

    console.log(`Queue: Processing item, queue size before: ${this._items.length + 1}`);
    
    try {
      this._pendingPromise = true;
      // Store the full item for itemAttributes to include currently running task
      this._currentItem = item;
      this._currentItemMetadata = (item.action && item.action.metadata) ? item.action.metadata : null;
      const payload = await item.action(this);
      this._pendingPromise = false;
      if (item.resolve) item.resolve(payload);
    } catch (e) {
      this._pendingPromise = false;
      if (item.reject) item.reject(e);
    } finally {
      this._currentItem = null;
      this._currentItemMetadata = null;
      console.log(`Queue: Finished processing item, queue size after: ${this._items.length}, continuing to next item...`);
      await this._saveQueueState();
      setTimeout(() => this.dequeue(), 0);
    }
    return true;
  }


  /**
   * Trigger queue processing - called after an operation stops
   * This allows the next queued item to start
   */
  triggerProcessing() {
    console.log("Queue: Trigger processing called - checking for next item");
    // Small delay to ensure flags are cleared
    setTimeout(() => this.dequeue(), 100);
  }

  get currentItemMetadata() { return this._currentItemMetadata || null; }
  async restoreFromStorage() { await this._initializePersistedQueue(); }
  async forceSave() { await this._saveQueueState(); }
}

export let processQueue = new AutoQueue();
