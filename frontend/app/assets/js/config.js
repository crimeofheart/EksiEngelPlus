import * as enums from './enums.js';
import * as utils from './utils.js';
import {log} from './log.js';

// Shared API key - embedded directly in the extension (not accessible to websites)
const SHARED_API_KEY = "cbjhsabj=iuhfnkenkfjnbekvbkjhdsbkjucbviujsdvnk./.d876fwuj*/8*f";

/**
 * Get the shared API key
 * @returns {string} The shared API key
 */
function getApiKey() {
  return SHARED_API_KEY;
}

export {getApiKey};

// Default config - apiKey loaded from getApiKey()
let defaultConfig = {
  "EksiSozlukURL": "https://eksisozluk.com",
  "whereIsEksiSozlukURL": "https://eksiengelplus.duzgun.org/api/where_is_eksisozluk",
  "serverURL": "https://eksiengelplus.duzgun.org/api/action/",
  "analyticsURL": "https://eksiengelplus.duzgun.org/admin/api/client_data/analytics",
  "apiKey": getApiKey(),
  "sendData": true,
  "sendLog": true,
  "enableLog": true,
  "logConsole": true,
  "enableNoobBan": true,
  "enableMute": true,
  "enableTitleBan": false,
  "enableAnalysisBeforeOperation": true,
  "enableOnlyRequiredActions": false,
  "enableProtectFollowedUsers": true,
  "banPremiumIcons": false,
  "enableDateFilter": false,
  "dateFilterRules": [],
  "configVersion": 0
};

/**
 * Raise this and add a step to handleConfig together.
 *
 * Absent on anything written before versioning existed, which is exactly the
 * set that needs correcting, so missing-means-zero is the behaviour we want.
 */
export const CURRENT_CONFIG_VERSION = 1;

/** The ten-year boundary the default rule carried before config v1. */
const LEGACY_DEFAULT_RULE_DAYS = 3650;

export let config = {...defaultConfig};

// Helper function to create default date filter rules
export function createDefaultDateFilterRules() {
  return [
    {
      id: "block-new-users",
      criteria: enums.DateFilterCriteria.NEWER_THAN,
      value: 5475,
      valueType: "years",
      action: enums.DateFilterAction.ENGELLE,
      description: "Yapılacak işlem 15 yıldan yeni hesapları kapsar",
      isDefault: true
    }
  ];
}

// Helper function to get default date bulk action config
export function createDefaultDateBulkConfig() {
  return {
    lastSource: enums.DateBulkSource.MUTED_USERS,
    lastCriteria: enums.DateFilterCriteria.OLDER_THAN,
    lastValue: 5475,
    lastValueType: "years",
    lastAction: enums.DateBulkAction.SESSIZDEN_CIKAR
  };
}

/**
 * Widens the untouched default rule from ten years to fifteen.
 *
 * Matched on the id and criteria as well as the old day count, so a user who
 * put their own number on this rule keeps it. Someone who deliberately typed
 * 3650 is indistinguishable from someone who never touched it and does get
 * rewritten -- the alternative leaves every existing install on the old
 * boundary forever, which is what a corrected default is meant to fix. The
 * direction is the safe one: a wider NEWER_THAN rule only spares more accounts.
 */
export function widenDefaultDateFilterRule(rules) {
  if (!Array.isArray(rules)) return rules;
  
  const fresh = createDefaultDateFilterRules()[0];
  return rules.map((rule) => {
    if (rule &&
        rule.id === fresh.id &&
        rule.criteria === fresh.criteria &&
        parseInt(rule.value, 10) === LEGACY_DEFAULT_RULE_DAYS) {
      return { ...rule, value: fresh.value, valueType: fresh.valueType, description: fresh.description };
    }
    return rule;
  });
}

export async function getConfig() {
  return new Promise((resolve) => {
    chrome.storage.local.get("config", (items) => {
      if (chrome.runtime.lastError) {
        log.err("config", `config could not be read: ${chrome.runtime.lastError.message}`);
        resolve(false);
        return;
      }
      if (items && items.config && Object.keys(items.config).length !== 0) resolve(items.config);
      else resolve(false);
    });
  });
}

export async function saveConfig(config) {
  return new Promise((resolve) => {
    chrome.storage.local.set({ "config": config }, () => {
      if (chrome.runtime.lastError) {
        log.err("config", `config could not be saved: ${chrome.runtime.lastError.message}`);
        resolve(false);
        return;
      }
      log.info("config", "A config saved into storage");
      resolve(true);
    });
  });
}

export async function handleConfig() {
  const c = await getConfig();
  
  // Set API key from getApiKey()
  config.apiKey = getApiKey();
  
  if (c) {
    log.info("config", "Config restored from storage");
    // Merge stored config
    Object.assign(config, c);
    // Ensure API key is set
    config.apiKey = getApiKey();
    
    // Read from the stored copy, not the merged one: this module's `config`
    // survives between calls, so a version left there by an earlier call would
    // make an unmigrated config look current.
    const storedVersion = c.configVersion || 0;
    let dirty = false;
    
    if (!config.dateFilterRules || config.dateFilterRules.length === 0) {
      log.info("config", "Applying default date filter rules");
      config.dateFilterRules = createDefaultDateFilterRules();
      dirty = true;
    } else if (storedVersion < 1) {
      // v1: the default rule widened from ten years to fifteen. A stored 3650
      // beats the corrected default, so the untouched rule is rewritten once
      // here rather than on every load.
      log.info("config", "Widening the default date filter rule to 15 years");
      config.dateFilterRules = widenDefaultDateFilterRule(config.dateFilterRules);
      dirty = true;
    }
    
    if (storedVersion < CURRENT_CONFIG_VERSION) {
      config.configVersion = CURRENT_CONFIG_VERSION;
      dirty = true;
    }
    
    if (dirty) saveConfig(config);
  } else {
    log.info("config", "No config in storage, hardcoded config will be saved into storage");
    config.dateFilterRules = createDefaultDateFilterRules();
    config.configVersion = CURRENT_CONFIG_VERSION;
    saveConfig(config);
  }
}
