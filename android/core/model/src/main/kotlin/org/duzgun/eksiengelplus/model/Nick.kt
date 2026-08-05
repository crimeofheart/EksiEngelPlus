package org.duzgun.eksiengelplus.model

/**
 * The single normalisation point for Ekşi nicknames.
 *
 * The extension repeats `name.replace(/ /gi, "-")` inline at roughly a dozen call
 * sites in scrapingHandler.js. That is what a reimplementation must not copy: one
 * missed site produces a URL that 404s, or a map key that silently fails to match
 * an already-scraped user.
 *
 * Not hypothetical -- live data from android-spike contained "0 derece" and
 * "ben ne diyorum sen ne diyorsun".
 */
fun String.toEksiSlug(): String = trim().replace(' ', '-')
