// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs
internal object SourceAliasGraph {
    fun resolve(source: String,next: (String)->String?): String {
        val seen=mutableSetOf<String>();var current=source
        while(true) {val target=next(current) ?: return current;check(seen.add(current)) {"Circular source replacement"};current=target}
    }
}
