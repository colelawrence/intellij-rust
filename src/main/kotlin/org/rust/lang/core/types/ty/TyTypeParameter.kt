/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.ty

import org.rust.ide.presentation.tyToString
import org.rust.lang.core.psi.RsTraitItem
import org.rust.lang.core.psi.RsTypeAlias
import org.rust.lang.core.psi.RsTypeParameter
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.types.BoundElement
import org.rust.lang.core.types.infer.TypeFolder
import org.rust.lang.core.types.infer.TypeVisitor

class TyTypeParameter private constructor(
    val parameter: TypeParameter,
    boundsSupplier: () -> Collection<BoundElement<RsTraitItem>>
) : Ty(HAS_TY_TYPE_PARAMETER_MASK) {

    private val bounds: Collection<BoundElement<RsTraitItem>> by lazy(LazyThreadSafetyMode.NONE, boundsSupplier)

    override fun equals(other: Any?): Boolean = other is TyTypeParameter && other.parameter == parameter
    override fun hashCode(): Int = parameter.hashCode()

    fun getTraitBoundsTransitively(): Collection<BoundElement<RsTraitItem>> =
        bounds.flatMap { it.flattenHierarchy }

    override fun superFoldWith(folder: TypeFolder): Ty {
        if (parameter is AssociatedType) {
            return associated(parameter.type.foldWith(folder), parameter.trait, parameter.target)
        }
        return super.superFoldWith(folder)
    }

    override fun superVisitWith(visitor: TypeVisitor): Boolean =
        (parameter as? AssociatedType)?.type?.visitWith(visitor) ?: false

    val name: String? get() = parameter.name

    override fun toString(): String = tyToString(this)

    interface TypeParameter {
        val name: String?
    }
    private object Self : TypeParameter {
        override val name: String? get() = "Self"
    }

    data class Named(val parameter: RsTypeParameter) : TypeParameter {
        override val name: String? get() = parameter.name
    }

    // TODO should be a separate Ty
    data class AssociatedType(val type: Ty, val trait: RsTraitItem, val target: RsTypeAlias) : TypeParameter {
        override val name: String? get() = "<$type as ${trait.name}>::${target.name}"
    }

    companion object {
        private val self = TyTypeParameter(Self, { emptyList() })

        fun self(): TyTypeParameter = self

        fun self(trait: RsTraitOrImpl): TyTypeParameter =
            TyTypeParameter(Self, { listOfNotNull(trait.implementedTrait) })

        fun named(parameter: RsTypeParameter): TyTypeParameter =
            TyTypeParameter(Named(parameter)) { bounds(parameter) }

        fun associated(target: RsTypeAlias): TyTypeParameter =
            associated(self(), target)

        private fun associated(type: Ty, target: RsTypeAlias): TyTypeParameter {
            val trait = target.parentOfType<RsTraitItem>()
                ?: error("Tried to construct an associated type from RsTypeAlias declared out of a trait")
            return associated(type, trait, target)
        }

        fun associated(type: Ty, trait: RsTraitItem, target: RsTypeAlias): TyTypeParameter {
            return TyTypeParameter(
                AssociatedType(type, trait, target),
                { emptyList() } )
        }
    }
}

private fun bounds(parameter: RsTypeParameter): List<BoundElement<RsTraitItem>> =
    parameter.bounds.mapNotNull { it.bound.traitRef?.resolveToBoundTrait }
