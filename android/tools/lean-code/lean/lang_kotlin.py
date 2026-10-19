# SPDX-License-Identifier: MIT
# Copyright 2026 Daniel Campos Ramos
from . import lang_jvm
KEY = 'kotlin'
MARKER = '//'
def lex(text): return lang_jvm.lex(KEY, text)
def refuse(text): return None
def joiner(text, start, end): return ' '
def verify(original, lean): return lang_jvm.verify(KEY, original, lean)
