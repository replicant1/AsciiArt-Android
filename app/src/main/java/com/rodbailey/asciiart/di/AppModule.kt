package com.rodbailey.asciiart.di

import com.rodbailey.asciiart.ui.AsciiPreviewViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val asciiPreviewPresentationModule = module {
    viewModelOf(::AsciiPreviewViewModel)
}

