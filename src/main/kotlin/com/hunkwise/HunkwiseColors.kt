package com.hunkwise

import java.awt.Color

/**
 * Centralized color palette for Hunkwise UI.
 * GitHub Dark-inspired theme — cohesive, high-contrast, easy on the eyes.
 */
object HunkwiseColors {

    // ── Backgrounds ─────────────────────────────────────────────────
    val BG           = Color(0x0D, 0x11, 0x17)   // #0D1117  main background
    val SURFACE      = Color(0x16, 0x1B, 0x22)   // #161B22  cards, panels
    val SURFACE_ALT  = Color(0x1C, 0x21, 0x28)   // #1C2128  headers, inputs
    val BORDER       = Color(0x30, 0x36, 0x3D)   // #30363D  borders

    // ── Text ────────────────────────────────────────────────────────
    val FG           = Color(0xE6, 0xED, 0xF3)   // #E6EDF3  primary text
    val MUTED        = Color(0x8B, 0x94, 0x9E)   // #8B949E  secondary text

    // ── Accents ─────────────────────────────────────────────────────
    val GREEN        = Color(0x3F, 0xB9, 0x50)   // #3FB950  success, additions
    val RED          = Color(0xF8, 0x51, 0x49)   // #F85149  errors, removals
    val BLUE         = Color(0x58, 0xA6, 0xFF)   // #58A6FF  links, info
    val ORANGE       = Color(0xD2, 0x99, 0x22)   // #D29922  warnings, accents
    val CYAN         = Color(0x39, 0xD2, 0xC0)   // #39D2C0  inline code
    val YELLOW       = Color(0xE3, 0xB3, 0x41)   // #E3B341  medium risk

    // ── Diff backgrounds ────────────────────────────────────────────
    val ADDED_BG     = Color(0x12, 0x26, 0x1E)   // #12261E  added line bg
    val REMOVED_BG   = Color(0x2D, 0x1B, 0x1B)   // #2D1B1B  removed line bg
    val ADDED_HIGHLIGHT = Color(0x2E, 0xA0, 0x43, 0x33)  // green with alpha

    // ── Editor action bar ───────────────────────────────────────────
    val ACCEPT       = Color(0x23, 0x8B, 0x3E)   // #238B3E  accept button
    val ACCEPT_HOVER = Color(0x2E, 0xA0, 0x43)   // #2EA043  accept hover
    val DISCARD      = Color(0xDA, 0x36, 0x33)   // #DA3633  discard button
    val DISCARD_HOVER= Color(0xF8, 0x51, 0x49)   // #F85149  discard hover
    val BAR_BG       = Color(0x0D, 0x11, 0x17, 0x10)  // subtle bar bg

    // ── Editor deleted lines ────────────────────────────────────────
    val DELETED_BG      = Color(0xF8, 0x51, 0x49, 0x1A)  // semi-transparent red
    val DELETED_LINE_BG = Color(0xF8, 0x51, 0x49, 0x10)  // lighter red stripe
    val DELETED_TEXT    = Color(0xF8, 0x51, 0x49)         // deleted line text

    // ── Permission blocks ───────────────────────────────────────────
    val PERMISSION_BORDER = Color(0x58, 0xA6, 0xFF)   // #58A6FF
    val PERMISSION_BG     = Color(0x0D, 0x15, 0x25)   // #0D1525

    // ── Code rendering ──────────────────────────────────────────────
    val CODE_BG      = Color(0x16, 0x1B, 0x22)   // same as SURFACE
    val CODE_FG      = Color(0xE6, 0xED, 0xF3)   // same as FG

    // ── Sender colors (chat) ────────────────────────────────────────
    val SENDER_USER   = Color(0x58, 0xA6, 0xFF)   // blue
    val SENDER_CLAUDE = Color(0xD2, 0x99, 0x22)   // orange
    val SENDER_SYSTEM = Color(0x3F, 0xB9, 0x50)   // green
    val SENDER_ERROR  = Color(0xF8, 0x51, 0x49)   // red
}
