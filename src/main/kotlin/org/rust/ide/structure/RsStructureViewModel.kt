/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.structure

import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.pom.Navigatable
import com.intellij.psi.NavigatablePsiElement
import org.rust.ide.presentation.getPresentationForStructure
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.RsElement
import org.rust.lang.core.psi.ext.RsMod
import org.rust.lang.core.psi.ext.RsNamedElement
import org.rust.stdext.buildList

class RsStructureViewModel(editor: Editor?, file: RsFile) : StructureViewModelBase(file, editor, RsStructureViewElement(file)),
                                                            StructureViewModel.ElementInfoProvider {
    init {
        withSuitableClasses(
            RsNamedElement::class.java,
            RsImplItem::class.java
        )
    }

    override fun isAlwaysShowsPlus(element: StructureViewTreeElement) = element.value is RsFile

    override fun isAlwaysLeaf(element: StructureViewTreeElement) =
        when (element.value) {
            is RsFieldDecl,
            is RsFunction,
            is RsModDeclItem,
            is RsConstant,
            is RsTypeAlias -> true
            else -> false
        }
}

private class RsStructureViewElement(
    val psi: RsElement
) : StructureViewTreeElement, Navigatable by (psi as NavigatablePsiElement) {

    override fun getValue() = psi

    override fun getPresentation(): ItemPresentation = getPresentationForStructure(psi)

    override fun getChildren(): Array<TreeElement> =
        childElements.sortedBy { it.textOffset }
            .map(::RsStructureViewElement).toTypedArray()

    private val childElements: List<RsElement>
        get() {
            return when (psi) {
                is RsEnumItem -> psi.enumBody?.enumVariantList.orEmpty()
                is RsImplItem, is RsTraitItem -> {
                    val members = (if (psi is RsImplItem) psi.members else (psi as RsTraitItem).members)
                        ?: return emptyList()
                    buildList {
                        addAll(members.functionList)
                        addAll(members.constantList)
                        addAll(members.typeAliasList)
                    }
                }
                is RsMod -> buildList {
                    addAll(psi.enumItemList)
                    addAll(psi.functionList)
                    addAll(psi.implItemList)
                    addAll(psi.modDeclItemList)
                    addAll(psi.modItemList)
                    addAll(psi.constantList)
                    addAll(psi.structItemList)
                    addAll(psi.traitItemList)
                    addAll(psi.typeAliasList)
                    addAll(psi.macroDefinitionList)
                    val foreignModItemList = psi.foreignModItemList
                    addAll(foreignModItemList.flatMap { it.functionList })
                    addAll(foreignModItemList.flatMap { it.constantList })
                }
                is RsStructItem -> psi.blockFields?.fieldDeclList.orEmpty()
                is RsEnumVariant -> psi.blockFields?.fieldDeclList.orEmpty()
                else -> emptyList()
            }
        }
}
