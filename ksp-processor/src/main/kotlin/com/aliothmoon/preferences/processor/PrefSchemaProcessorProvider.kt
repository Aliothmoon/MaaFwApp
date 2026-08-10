package com.aliothmoon.preferences.processor

import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider

class PrefSchemaProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return PrefSchemaProcessor(
            codeGenerator = environment.codeGenerator,
            logger = environment.logger
        )
    }
}
