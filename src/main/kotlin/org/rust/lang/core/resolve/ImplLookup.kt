/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve

import com.intellij.openapi.project.Project
import org.rust.lang.core.psi.RsImplItem
import org.rust.lang.core.psi.RsTraitItem
import org.rust.lang.core.psi.RsTypeAlias
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.resolve.indexes.RsImplIndex
import org.rust.lang.core.resolve.indexes.RsLangItemIndex
import org.rust.lang.core.stubs.index.RsNamedElementIndex
import org.rust.lang.core.types.BoundElement
import org.rust.lang.core.types.TraitRef
import org.rust.lang.core.types.infer.*
import org.rust.lang.core.types.ty.*
import org.rust.lang.core.types.ty.Mutability.IMMUTABLE
import org.rust.lang.core.types.ty.Mutability.MUTABLE
import org.rust.lang.core.types.type
import org.rust.openapiext.ProjectCache
import org.rust.stdext.buildList
import org.rust.stdext.concatLists
import kotlin.LazyThreadSafetyMode.NONE

enum class StdDerivableTrait(val modName: String, val dependencies: Array<StdDerivableTrait> = emptyArray()) {
    Clone("clone"),
    Copy("marker", arrayOf(Clone)),
    Debug("fmt"),
    Default("default"),
    Hash("hash"),
    PartialEq("cmp"),
    Eq("cmp", arrayOf(PartialEq)),
    PartialOrd("cmp", arrayOf(PartialEq)),
    Ord("cmp", arrayOf(PartialOrd, Eq, PartialEq))
}

val StdDerivableTrait.withDependencies: List<StdDerivableTrait> get() = listOf(this, *dependencies)

val STD_DERIVABLE_TRAITS: Map<String, StdDerivableTrait> = StdDerivableTrait.values().associate { it.name to it }

private val RsTraitItem.typeParamSingle: TyTypeParameter?
    get() =
        typeParameterList?.typeParameterList?.singleOrNull()?.let { TyTypeParameter.named(it) }

const val DEFAULT_RECURSION_LIMIT = 64

class ImplLookup(
    private val project: Project,
    private val items: StdKnownItems,
    private var _ctx: RsInferenceContext? = null
) {
    // Non-concurrent HashMap and lazy(NONE) are safe here because this class isn't shared between threads
    private val primitiveTyHardcodedImplsCache = mutableMapOf<TyPrimitive, Collection<BoundElement<RsTraitItem>>>()
    private val binOpsTraitAndOutputCache = mutableMapOf<ArithmeticOp, Pair<RsTraitItem, RsTypeAlias>?>()
    private val fnTraits by lazy(NONE) {
        listOf("fn", "fn_mut", "fn_once").mapNotNull { RsLangItemIndex.findLangItem(project, it) }
    }
    private val fnOnceOutput: RsTypeAlias? by lazy(NONE) {
        val trait = RsLangItemIndex.findLangItem(project, "fn_once") ?: return@lazy null
        findAssociatedType(trait, "Output")
    }
    val fnOutputParam by lazy(NONE) {
        fnOnceOutput?.let { TyTypeParameter.associated(it) }
    }
    private val copyTrait: RsTraitItem? by lazy(NONE) {
        RsNamedElementIndex.findDerivableTraits(project, "Copy").firstOrNull()
    }
    private val derefTraitAndTarget: Pair<RsTraitItem, RsTypeAlias>? = run {
        val trait = RsLangItemIndex.findLangItem(project, "deref") ?: return@run null
        findAssociatedType(trait, "Target")?.let { trait to it }
    }
    private val indexTraitAndOutput: Pair<RsTraitItem, RsTypeAlias>? by lazy(NONE) {
        val trait = RsLangItemIndex.findLangItem(project, "index") ?: return@lazy null
        findAssociatedType(trait, "Output")?.let { trait to it }
    }
    private val iteratorTraitAndOutput: Pair<RsTraitItem, RsTypeAlias>? by lazy(NONE) {
        val trait = items.findIteratorTrait() ?: return@lazy null
        findAssociatedType(trait, "Item")?.let { trait to it }
    }
    private val intoIteratorTraitAndOutput: Pair<RsTraitItem, RsTypeAlias>? by lazy(NONE) {
        val trait = items.findCoreItem("iter::IntoIterator") as? RsTraitItem ?: return@lazy null
        findAssociatedType(trait, "Item")?.let { trait to it }
    }

    private val ctx: RsInferenceContext
        get() {
            if (_ctx == null) _ctx = RsInferenceContext()
            return _ctx!!
        }

    fun findImplsAndTraits(ty: Ty): Collection<RsTraitOrImpl> {
        return findImplsAndTraitsCache.getOrPut(project, freshen(ty)) { rawFindImplsAndTraits(ty) }
    }

    private fun rawFindImplsAndTraits(ty: Ty): Collection<RsTraitOrImpl> {
        return when (ty) {
            is TyTypeParameter -> ty.getTraitBoundsTransitively().map { it.element }
            is TyTraitObject -> BoundElement(ty.trait).flattenHierarchy.map { it.element }
            is TyFunction -> {
                findSimpleImpls(ty) + fnTraits
            }
            is TyUnknown -> emptyList()
            else -> concatLists(
                findDerivedTraits(ty),
                getHardcodedImpls(ty).map { it.element },
                findSimpleImpls(ty)
            )
        }
    }

    private fun findDerivedTraits(ty: Ty): Collection<RsTraitItem> {
        return (ty as? TyStructOrEnumBase)?.item?.derivedTraits.orEmpty()
            // select only std traits because we are sure
            // that they are resolved correctly
            .filter { it.isStdDerivable }
    }

    private fun getHardcodedImpls(ty: Ty): Collection<BoundElement<RsTraitItem>> {
        // TODO this code should be completely removed after macros implementation
        when (ty) {
            is TyPrimitive -> {
                return primitiveTyHardcodedImplsCache.getOrPut(ty) {
                    val impls = mutableListOf<BoundElement<RsTraitItem>>()
                    if (ty is TyNumeric) {
                        // libcore/ops/arith.rs libcore/ops/bit.rs
                        impls += items.findBinOpTraits().map { it.substAssocType("Output", ty) }
                    }
                    if (ty != TyStr) {
                        // libcore/cmp.rs
                        if (ty != TyUnit) {
                            RsLangItemIndex.findLangItem(project, "eq")?.let {
                                impls.add(BoundElement(it, it.typeParamSingle?.let { mapOf(it to ty) } ?: emptySubstitution))
                            }
                        }
                        if (ty != TyUnit && ty != TyBool) {
                            RsLangItemIndex.findLangItem(project, "ord")?.let {
                                impls.add(BoundElement(it, it.typeParamSingle?.let { mapOf(it to ty) } ?: emptySubstitution))
                            }
                        }
                        if (ty !is TyFloat) {
                            items.findEqTrait()?.let { impls.add(BoundElement(it)) }
                            if (ty != TyUnit && ty != TyBool) {
                                items.findOrdTrait()?.let { impls.add(BoundElement(it)) }
                            }
                        }

                    }
                    // libcore/clone.rs
                    items.findCloneTrait()?.let { impls.add(BoundElement(it)) }
                    impls
                }
            }
            is TyStruct -> when {
                ty.item == items.findCoreItem("slice::Iter") -> {
                    val trait = items.findIteratorTrait() ?: return emptyList()
                    return listOf(trait.substAssocType("Item",
                        TyReference(ty.typeParameterValues.valueByName("T"), IMMUTABLE)))
                }
                ty.item == items.findCoreItem("slice::IterMut") -> {
                    val trait = items.findIteratorTrait() ?: return emptyList()
                    return listOf(trait.substAssocType("Item",
                        TyReference(ty.typeParameterValues.valueByName("T"), MUTABLE)))
                }
            }
        }
        return emptyList()
    }

    private fun findSimpleImpls(selfTy: Ty): Collection<RsImplItem> {
        return RsImplIndex.findPotentialImpls(project, selfTy).mapNotNull { impl ->
            val subst = impl.generics.associate { it to ctx.typeVarForParam(it) }
            val formalSelfTy = impl.typeReference?.type?.substitute(subst) ?: return@mapNotNull null
            impl.takeIf { ctx.canCombineTypes(formalSelfTy, selfTy) }
        }
    }

    /**
     * If the TraitRef is a something like
     *     `T : Foo<U>`
     * here we select an impl of the trait `Foo<U>` for the type `T`, i.e.
     *     `impl Foo<U> for T {}`
     */
    fun select(ref: TraitRef, recursionDepth: Int = 0): SelectionResult<Selection> {
        if (recursionDepth > DEFAULT_RECURSION_LIMIT) return SelectionResult.Err()
        return traitSelectionCache.getOrPut(project, freshen(ref)) { selectCandidate(ref) }
            .map { confirmCandidate(ref, recursionDepth, it) }
    }

    private fun selectCandidate(ref: TraitRef): SelectionResult<SelectionCandidate> {
        val candidates = assembleCandidates(ref)

        return when (candidates.size) {
            0 -> SelectionResult.Err()
            1 -> SelectionResult.Ok(candidates.single())
            else -> SelectionResult.Ambiguous()
        }
    }

    /**
     * When we doing an independent cache lookups we want to treat
     * these types as the same (A & B is type variables)
     * ` S<A, B, A> == S<B, A, B>`
     */
    private fun <T : TypeFoldable<T>> freshen(ty: T): T {
        var counter = 0
        val map = HashMap<TyInfer, FreshTyInfer>()

        return ty.foldTyInferWith {
            map.getOrPut(it) {
                when (it) {
                    is TyInfer.TyVar -> FreshTyInfer.TyVar(counter++)
                    is TyInfer.IntVar -> FreshTyInfer.IntVar(counter++)
                    is TyInfer.FloatVar -> FreshTyInfer.FloatVar(counter++)
                }
            }
        }
    }

    private fun assembleCandidates(ref: TraitRef): List<SelectionCandidate> = buildList {
        addAll(assembleImplCandidates(ref))
        addAll(assembleDerivedCandidates(ref))
        if (ref.selfTy is TyFunction && ref.trait.element in fnTraits) add(SelectionCandidate.Closure)
        if (ref.selfTy is TyTypeParameter) {
            val bound = ref.selfTy.getTraitBoundsTransitively().find { it.element == ref.trait.element }
            if (bound != null) add(SelectionCandidate.TypeParameter(bound))
        }
        getHardcodedImpls(ref.selfTy).find { it.element == ref.trait.element }
            ?.let { add(SelectionCandidate.TypeParameter(it)) }
    }

    private fun assembleImplCandidates(ref: TraitRef): List<SelectionCandidate> {
        return RsImplIndex.findPotentialImpls(project, ref.selfTy)
            .mapNotNull { impl ->
                val boundTrait = impl.implementedTrait ?: return@mapNotNull null
                if (boundTrait.element != ref.trait.element) return@mapNotNull null
                val subst = impl.generics.associate { it to ctx.typeVarForParam(it) }
                val formalSelfTy = impl.typeReference?.type?.substitute(subst) ?: return@mapNotNull null
                val boundSubst = boundTrait.substitute(subst).subst.mapValues { (k, v) ->
                    if (k == v && v is TyTypeParameter && v.parameter is TyTypeParameter.Named) {
                        v.parameter.parameter.typeReference?.type?.substitute(subst) ?: v
                    } else {
                        v
                    }
                }.substituteInValues(mapOf(TyTypeParameter.self() to ref.selfTy))
                val implTraitRef = TraitRef(formalSelfTy, BoundElement(boundTrait.element, boundSubst))
                if (!ctx.probe { ctx.combineTraitRefs(implTraitRef, ref) }) return@mapNotNull null
                SelectionCandidate.Impl(impl, subst, implTraitRef)
            }
    }

    private fun assembleDerivedCandidates(ref: TraitRef): List<SelectionCandidate> {
        return (ref.selfTy as? TyStructOrEnumBase)?.item?.derivedTraits.orEmpty()
            // select only std traits because we are sure
            // that they are resolved correctly
            .filter { it.isStdDerivable }
            .filter { it == ref.trait.element }
            .map { SelectionCandidate.DerivedTrait(it) }
    }

    private fun confirmCandidate(
        ref: TraitRef,
        recursionDepth: Int = 0,
        candidate: SelectionCandidate
    ): Selection {
        val newRecDepth = recursionDepth + 1
        return when (candidate) {
            is SelectionCandidate.Impl -> {
                ctx.combineTraitRefs(ref, candidate.ref)
                val candidateSubst = candidate.subst + mapOf(TyTypeParameter.self() to ref.selfTy)
                val obligations = candidate.item.bounds.asSequence()
                    .map { it.substitute(candidateSubst) }
                    .map { ctx.normalizeAssociatedTypesIn(it, recursionDepth) }
                    .flatMap { it.obligations.asSequence() + Obligation(newRecDepth, Predicate.Trait(it.value)) }
                    .toList()
                Selection(candidate.item, obligations, candidateSubst)
            }
            is SelectionCandidate.DerivedTrait -> Selection(candidate.item, emptyList())
            is SelectionCandidate.Closure -> {
                // TODO hacks hacks hacks
                val (trait, subst) = ref.trait
                val predicate = Predicate.Equate(subst[fnOutputParam] ?: TyUnit, (ref.selfTy as TyFunction).retType)
                val obligations = if (predicate.ty1 != predicate.ty2) {
                    listOf(Obligation(
                        newRecDepth,
                        predicate
                    ))
                } else {
                    emptyList()
                }
                Selection(trait, obligations)
            }
            is SelectionCandidate.TypeParameter -> {
                okResultFor(candidate.bound, ref.trait.subst, recursionDepth)
            }
        }
    }

    private fun okResultFor(
        impl: BoundElement<RsTraitOrImpl>,
        subst: Substitution,
        recursionDepth: Int
    ): Selection {
        val (found, foundSubst) = impl
        return Selection(found, subst.mapNotNull { (k, ty1) ->
            foundSubst[k]?.let { ty2 ->
                Obligation(
                    recursionDepth + 1,
                    Predicate.Equate(ty1, ty2)
                )
            }
        })
    }

    fun coercionSequence(baseTy: Ty): Sequence<Ty> {
        val result = mutableSetOf<Ty>()
        return generateSequence(baseTy) {
            if (result.add(it)) {
                deref(it) ?: (it as? TyArray)?.let { TySlice(it.base) }
            } else {
                null
            }
        }.map(ctx::shallowResolve).constrainOnce().take(DEFAULT_RECURSION_LIMIT)
    }

    fun deref(ty: Ty): Ty? = when (ty) {
        is TyReference -> ty.referenced
        is TyPointer -> ty.referenced
        else -> findDerefTarget(ty)?.value // TODO don't ignore obligations
    }

    private fun findDerefTarget(ty: Ty): TyWithObligations<Ty>? {
        return selectProjection(derefTraitAndTarget ?: return null, ty).ok()
    }

    fun findIteratorItemType(ty: Ty): TyWithObligations<Ty>? {
        return selectProjection(iteratorTraitAndOutput ?: return legacyFindIteratorItemType(ty), ty).ok()
            ?: selectProjection(intoIteratorTraitAndOutput ?: return null, ty).ok()
    }

    // TODO legacy; used in tests only; should be removed
    private fun legacyFindIteratorItemType(ty: Ty): TyWithObligations<Ty>? {
        val impl = findImplsAndTraits(ty)
            .find { impl ->
                val traitName = impl.implementedTrait?.element?.name
                traitName == "Iterator" || traitName == "IntoIterator"
            } ?: return null

        val rawType = lookupAssociatedType(impl, "Item")
        return TyWithObligations(rawType, emptyList())
    }

    fun findIndexOutputType(containerType: Ty, indexType: Ty): TyWithObligations<Ty>? {
        return selectProjection(indexTraitAndOutput ?: return null, containerType, indexType).ok()
    }

    fun findArithmeticBinaryExprOutputType(lhsType: Ty, rhsType: Ty, op: ArithmeticOp): TyWithObligations<Ty>? {
        val traitAndOutput = binOpsTraitAndOutputCache.getOrPut(op) {
            val trait = RsLangItemIndex.findLangItem(project, op.itemName, op.modName) ?: return@getOrPut null
            findAssociatedType(trait, "Output")?.let { trait to it }
        } ?: return null
        return selectProjection(traitAndOutput, lhsType, rhsType).ok()
    }

    private fun selectProjection(
        traitAndOutput: Pair<RsTraitItem, RsTypeAlias>,
        selfTy: Ty,
        vararg subst: Ty
    ): SelectionResult<TyWithObligations<Ty>?> {
        val (trait, assocType) = traitAndOutput
        return selectProjection(TraitRef(selfTy, trait.withSubst(*subst)), assocType)
    }

    fun selectProjection(
        ref: TraitRef,
        assocType: RsTypeAlias,
        recursionDepth: Int = 0
    ): SelectionResult<TyWithObligations<Ty>?> {
        return select(ref, recursionDepth).map {
            lookupAssociatedType(ref.selfTy, it, assocType)
                ?.let { ctx.normalizeAssociatedTypesIn(it, recursionDepth) }
                ?.withObligations(it.nestedObligations)
        }
    }

    private fun lookupAssociatedType(selfTy: Ty, res: Selection, assocType: RsTypeAlias): Ty? {
        if (selfTy is TyTypeParameter) {
            return lookupAssocTypeInBounds(selfTy.getTraitBoundsTransitively(), res.impl, assocType)
        }
        val ty = lookupAssocTypeInSelection(res, assocType) ?:
            lookupAssocTypeInBounds(getHardcodedImpls(selfTy), res.impl, assocType)
        return ty?.substitute(mapOf(TyTypeParameter.self() to selfTy))
    }

    private fun lookupAssocTypeInSelection(selection: Selection, assoc: RsTypeAlias): Ty? =
        selection.impl.associatedTypesTransitively.find { it.name == assoc.name }?.typeReference?.type?.substitute(selection.subst)

    private fun lookupAssocTypeInBounds(
        subst: Collection<BoundElement<RsTraitItem>>,
        trait: RsTraitOrImpl,
        assocType: RsTypeAlias
    ): Ty? {
        return subst
            .find { it.element == trait }
            ?.subst
            ?.get(TyTypeParameter.associated(assocType))
    }

    fun findOverloadedOpImpl(lhsType: Ty, rhsType: Ty, op: OverloadableBinaryOperator): RsTraitOrImpl? {
        val trait = RsLangItemIndex.findLangItem(project, op.itemName, op.modName) ?: return null
        return select(TraitRef(lhsType, trait.withSubst(rhsType))).ok()?.impl
    }

    fun asTyFunction(ty: Ty): TyWithObligations<TyFunction>? {
        return (ty as? TyFunction)?.withObligations() ?: run {
            val output = fnOnceOutput ?: return@run null

            val inputArgVar = TyInfer.TyVar()
            val ok = fnTraits.asSequence()
                .mapNotNull { selectProjection(it to output, ty, inputArgVar).ok() }
                .firstOrNull() ?: return@run null
            TyWithObligations(
                TyFunction((ctx.shallowResolve(inputArgVar) as? TyTuple)?.types.orEmpty(), ok.value),
                ok.obligations
            )
        }
    }

    fun asTyFunction(ref: BoundElement<RsTraitItem>): TyFunction? {
        return ref.asFunctionType
    }

    fun isCopy(ty: Ty): Boolean = findImplsAndTraits(ty).any { it == copyTrait }

    private val BoundElement<RsTraitItem>.asFunctionType: TyFunction?
        get() {
            val outputParam = fnOutputParam ?: return null
            val param = element.typeParamSingle ?: return null
            val argumentTypes = ((subst[param] ?: TyUnknown) as? TyTuple)?.types.orEmpty()
            val outputType = (subst[outputParam] ?: TyUnit)
            return TyFunction(argumentTypes, outputType)
        }

    companion object {
        fun relativeTo(psi: RsElement): ImplLookup =
            ImplLookup(psi.project, StdKnownItems.relativeTo(psi))

        private val findImplsAndTraitsCache =
            ProjectCache<Ty, Collection<RsTraitOrImpl>>("findImplsAndTraitsCache")

        private val traitSelectionCache =
            ProjectCache<TraitRef, SelectionResult<SelectionCandidate>>("traitSelectionCache")
    }
}

sealed class SelectionResult<out T> {
    class Err<out T> : SelectionResult<T>()
    class Ambiguous<out T> : SelectionResult<T>()
    data class Ok<out T>(
        val result: T
    ) : SelectionResult<T>()

    fun ok(): T? = (this as? Ok<T>)?.result

    inline fun <R> map(action: (T) -> R): SelectionResult<R> = when (this) {
        is SelectionResult.Err -> SelectionResult.Err()
        is SelectionResult.Ambiguous -> SelectionResult.Ambiguous()
        is SelectionResult.Ok -> SelectionResult.Ok(action(result))
    }
}

data class Selection(
    val impl: RsTraitOrImpl,
    val nestedObligations: List<Obligation>,
    val subst: Substitution = emptySubstitution
)

fun RsTraitItem.withSubst(vararg subst: Ty): BoundElement<RsTraitItem> {
    val subst1 = typeParameterList?.typeParameterList?.withIndex()?.associate { (i, par) ->
        val param = TyTypeParameter.named(par)
        param to (subst.getOrNull(i) ?: param)
    }

    return BoundElement(this, subst1 ?: emptySubstitution)
}

private sealed class SelectionCandidate {
    /**
     * ```
     * impl<A, B> Foo<A> for Bar<B> {}
     * |   |      |
     * |   |      ref
     * |   subst
     * item
     * ```
     */
    data class Impl(
        val item: RsImplItem,
        val subst: Substitution,
        val ref: TraitRef
    ) : SelectionCandidate()

    data class DerivedTrait(val item: RsTraitItem) : SelectionCandidate()
    data class TypeParameter(val bound: BoundElement<RsTraitItem>) : SelectionCandidate()
    object Closure : SelectionCandidate()
}

private fun RsTraitItem.substAssocType(assoc: String, ty: Ty?): BoundElement<RsTraitItem> {
    val assocType = findAssociatedType(this, assoc)?.let { TyTypeParameter.associated(it) }
    val subst = if (assocType != null && ty != null) mapOf(assocType to ty) else emptySubstitution
    return BoundElement(this, subst)
}

private fun Substitution.valueByName(name: String): Ty =
    entries.find { it.key.toString() == name }?.value ?: TyUnknown

private fun lookupAssociatedType(impl: RsTraitOrImpl, name: String): Ty {
    return impl.associatedTypesTransitively
        .find { it.name == name }
        ?.let { it.typeReference?.type ?: TyTypeParameter.associated(it) }
        ?: TyUnknown
}

private fun findAssociatedType(baseTrait: RsTraitItem, name: String): RsTypeAlias? =
    baseTrait.associatedTypesTransitively.find { it.name == name }

private fun <T : Ty> T.withObligations(obligations: List<Obligation> = emptyList()): TyWithObligations<T> =
    TyWithObligations(this, obligations)
