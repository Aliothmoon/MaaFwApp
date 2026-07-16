package com.aliothmoon.maafw.domain

enum class DiagnosticSeverity { Warning, Error }

/**
 * 结构化诊断，分层归属见 docs/persistence-diagnostics.md：
 * ProjectLoad（PI 解析期）/ Session（解析投影期）/ Runtime（RunPlanBuilder）。
 */
data class Diagnostic(
    val severity: DiagnosticSeverity,
    val source: String,
    val message: String,
)
