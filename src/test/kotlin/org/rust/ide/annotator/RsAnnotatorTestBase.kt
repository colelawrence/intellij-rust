/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.annotator

import com.intellij.testFramework.fixtures.impl.CodeInsightTestFixtureImpl
import org.intellij.lang.annotations.Language
import org.rust.FileTree
import org.rust.lang.RsTestBase

abstract class RsAnnotatorTestBase : RsTestBase() {
    protected fun doTest(vararg additionalFilenames: String) {
        myFixture.testHighlighting(fileName, *additionalFilenames)
    }

    protected fun checkInfo(@Language("Rust") text: String) {
        myFixture.configureByText("main.rs", text)
        myFixture.testHighlighting(false, true, false)
    }

    protected fun checkWarnings(@Language("Rust") text: String) {
        myFixture.configureByText("main.rs", text)
        myFixture.testHighlighting(true, false, true)
    }

    protected fun checkErrors(@Language("Rust") text: String) {
        myFixture.configureByText("main.rs", text)
        myFixture.testHighlighting(false, false, false)
    }

    protected fun checkQuickFix(
        fixName: String,
        @Language("Rust") before: String,
        @Language("Rust") after: String
    ) = checkByText(before, after) { applyQuickFix(fixName) }

    protected fun checkDontTouchAstInOtherFiles(fileTree: FileTree, checkInfo: Boolean = false, filePath: String? = null) {
        fileTree.create()
        myFixture.configureFromTempProjectFile("main.rs")

        (myFixture as CodeInsightTestFixtureImpl) // meh
            .setVirtualFileFilter { !it.path.endsWith("main.rs") }
        if (filePath == null) {
            myFixture.testHighlighting(false, checkInfo, false)
        } else {
            myFixture.testHighlighting(false, checkInfo, false, filePath)
        }
    }

}

