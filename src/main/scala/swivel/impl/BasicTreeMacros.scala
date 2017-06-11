//
// BasicTreeMacros.scala -- Scala macro implementation BasicTreeMacros
// Project swivel
//
// Created by amp on Jun, 2017.
//
// Copyright (c) 2017 The University of Texas at Austin. All rights reserved.
//
// Use and redistribution of this file is governed by the license terms in
// the LICENSE file found in the project's top-level directory and also found at
// URL: http://orc.csres.utexas.edu/license.shtml .
//

package swivel.impl

import scala.reflect.macros.whitebox.Context 
import scala.language.existentials
import scala.reflect.internal.Flags

object BasicTreeMacros {
  class CompileErrorReported() extends Exception
}

class BasicTreeMacros(val c: Context) {
  import BasicTreeMacros._
  import c.universe._
  
  object Builtins {
    val subtree = c.typecheck(tq"_root_.swivel.subtree", c.TYPEmode)
    val subtreeSym = subtree.symbol
    val replacement = c.typecheck(tq"_root_.swivel.replacement", c.TYPEmode)
    val replacementSym = replacement.symbol
    val transform = c.typecheck(tq"_root_.swivel.transform", c.TYPEmode)
    val transformSym = transform.symbol
    val Zipper = c.typecheck(tq"_root_.swivel.Zipper", c.TYPEmode)
    val ZipperSym = Zipper.symbol
    val ZipperParent = c.typecheck(tq"_root_.swivel.ZipperParent", c.TYPEmode)
    val ZipperParentSym = ZipperParent.symbol
    val ZipperReplaceable = c.typecheck(tq"_root_.swivel.ZipperReplaceable", c.TYPEmode)
    val ZipperReplaceableSym = ZipperReplaceable.symbol
    val ZipperTransformable = c.typecheck(tq"_root_.swivel.ZipperTransformable", c.TYPEmode)
    val ZipperTransformableSym = ZipperTransformable.symbol
    val SwivelValue = c.typecheck(tq"_root_.swivel.SwivelValue", c.TYPEmode)
    val SwivelValueSym = SwivelValue.symbol
  }
  
  def extractExpandees(inputs: List[Tree]) = { 
    val (annottee, expandees) = inputs match {
      case (param: ValDef) :: (rest @ (_ :: _)) => (param, rest)
      case (param: TypeDef) :: (rest @ (_ :: _)) => (param, rest)
      case _ => (EmptyTree, inputs)
    }
    q"..$expandees"
  }

  def extractClassAndObject(macroName: String, annottees: Seq[Tree]) = {
    val (inCls, inObj) = annottees.toList match {
      case List(c: ClassDef, o: ModuleDef) =>
        (c, Some(o))
      case List(c: ClassDef) =>
        (c, None)
      case _ =>
        c.error(c.enclosingPosition, s"@$macroName may only be applied to classes.")
        throw new CompileErrorReported()
    }
    (inCls, inObj)
  }
  
  def extractSwivelDirectSuperClassName(supers: Seq[Tree]): TypeName = supers.headOption match {
    case Some(Ident(n@TypeName(_))) => n
    case n =>
      c.error(n.map(_.pos).getOrElse(c.enclosingPosition), "The class annotated with @branch must have the swivel class as the first superclass.")
      throw new CompileErrorReported()
  }
  
  def extractDefinition(arg: Tree): ValDef = arg match {
    case d: ValDef => 
      d
    case _ =>
      c.error(arg.pos, s"Must be a simple definition.")
      throw new CompileErrorReported()
  }
  
  def isSeqRef(d: Tree): Boolean = {
    d match {
      case tq"$prefix.Seq" =>
        true
      case tq"Seq" =>
        true
      case _ =>
        false
    }
  }
      
  def isMapRef(d: Tree): Boolean = {
    d match {
      case tq"$prefix.Map" =>
        true
      case tq"Map" =>
        true
      case _ =>
        false
    }
  }
      
  def isOptionRef(d: Tree): Boolean = {
    d match {
      case tq"$prefix.Option" =>
        true
      case tq"Option" =>
        true
      case _ =>
        false
    }
  }
      
  def isSubtree(d: ValDef): Boolean = {
    val r = d.mods.annotations.exists({
      case v@q"new ${Ident(n)}()" if n == Builtins.subtreeSym.name =>
        true
      case v =>
        false
    })
    //println(s"isSubtree: $r $d ${showRaw(d)}")
    r
  }
      
  def addZ(n: TypeName) = TypeName(n.decodedName + "Z")
  def addZP(n: TypeName) = TypeName(n.decodedName + "Z$P")
  def addSubtreeName(n: TypeName, subName: Name) = TypeName(n.decodedName + "$" + subName.decodedName)
  
  def getFlag(mods: Modifiers, flag: FlagSet): Boolean = mods match {
    case Modifiers(flags, p, anns) =>
      (flags | flag) == flags
  }
  
  def addFinal(mods: Modifiers): Modifiers = mods match {
    case Modifiers(flags, p, anns) =>
      Modifiers(flags | Flag.FINAL, p, anns)
  }
  def addCase(mods: Modifiers): Modifiers = mods match {
    case Modifiers(flags, p, anns) =>
      Modifiers(flags | Flag.CASE, p, anns)
  }
  
  val castName = TermName("swivel_cast")
  val putName = TermName("swivel_put")
  val checkSubtreeName = TermName("swivel_checkSubtree")
  val parentName = TermName("swivel_parent")
  val underlyingName = TermName("swivel_underlying")
  val indexName = TermName("swivel_index")
  val keyName = TermName("swivel_key")
  def parentStringName = TermName("swivel_parentString")
  
  val reservedFieldNames = Set(
      "value",
      "toZipper",
      "subtrees",
      "parent",
      "root",
      "replace",
      checkSubtreeName.toString(),
      putName.toString(),
      castName.toString(),
      parentName.toString(),
      underlyingName.toString()
      ).map(TermName(_))
  
  def checkFieldName(d: DefTree): Unit = {
    if (reservedFieldNames.contains(d.name.toTermName)) {
      c.error(d.pos, s"The name ${d.name} is reserved by swivel. (The full list of reserved names is: ${reservedFieldNames.mkString(", ")})")
    }
  }
  
  def buildCastMethods(name: TypeName): Seq[Tree] = {
    val v = c.freshName[TermName](TermName("v"))
    val v1 = c.freshName[TermName](TermName("v1"))
    
    Seq(
    q"""
      @inline
      def $castName($v: ${name}#RootValue): ${name} = $v match {
        case $v1: ${name} => $v1
        case _ =>
          throw new IllegalArgumentException($v + " provided where " + ${name.toString()} + " expected.")
      }
      """,
    q"""
      @inline
      def $checkSubtreeName($v: ${name}#RootValue): Unit = $v match {
        case _: ${name} => ()
        case _ =>
          throw new IllegalArgumentException($v + " provided where " + ${name.toString()} + " expected.")
      }
      """
    )
  }
  
  def buildReplacementSupport(mods: Modifiers): (Seq[Tree], Seq[Tree]) = {
    val replacementValueTypes = mods.annotations.collect({
      case v@q"new ${Ident(n)}[$replacmentValueType]()" if n == Builtins.replacementSym.name =>
        replacmentValueType
    })
    
    replacementValueTypes.toSeq match {
      case Seq(ty) =>
        (Seq(tq"${Builtins.ZipperReplaceableSym}"), Seq(q"type ReplacementValue = $ty"))        
      case Seq() =>
        (Seq(), Seq())
      case ty +: _ =>
        c.error(ty.pos, s"@replacement may only be specified once.")
        (Seq(), Seq())
    }
  }
  
  def buildTransformSupportNonleaf(mods: Modifiers): (Seq[Tree], Seq[Tree]) = {
    getTransformAnnotationInfo(mods) match {
      case Some(EmptyTree) =>
        c.error(c.enclosingPosition, "@transform on non-leaf nodes must have a function type.")
        (Seq(), Seq())
      case Some(transformFunctionType) =>
        (Seq(tq"${Builtins.ZipperTransformableSym}"), Seq(q"type TranformFunction = $transformFunctionType"))
      case None =>
        (Seq(), Seq())
    }
  }
  
  def hasTransformSupportLeaf(mods: Modifiers): Boolean = {
    getTransformAnnotationInfo(mods) match {
      case Some(EmptyTree) =>
        true
      case Some(ty) =>
        c.warning(ty.pos, "The argument to @transform on leafs is ignored.")
        true
      case None =>
        false
    }
  }
  
  def getTransformAnnotationInfo(mods: Modifiers): Option[Tree] = {
    val transformValueTypes = mods.annotations.collect({
      case v@q"new ${Ident(n)}()" if n == Builtins.transformSym.name =>
        EmptyTree
      case v@q"new ${Ident(n)}[$replacmentValueType]()" if n == Builtins.transformSym.name =>
        replacmentValueType
    })
    
    transformValueTypes.toSeq match {
      case Seq(ty) =>
        Some(ty)
      case Seq() =>
        None
      case ty +: _ =>
        c.error(ty.pos, "@transform may only be specified once.")
        Some(ty)
    }
  }
  
  def cleanedMods(mods: Modifiers): Modifiers = {
    mods match {
      case Modifiers(flags, p, anns) =>
        Modifiers(flags, p, anns.filter({
          case v@q"new ${Ident(n)}[${targs}]()" if n == Builtins.replacementSym.name =>
            false
          case v@q"new ${Ident(n)}[${targs}]()" if n == Builtins.transformSym.name =>
            false
          case v@q"new ${Ident(n)}()" if n == Builtins.transformSym.name =>
            false
          case v =>
            true
        }))
    }
  }
  
  def root(annottees: Tree*): Tree = {
    try {
      val (inCls, inObj) = extractClassAndObject("root", annottees)
      
      val (mods, name, supers) = inCls match {
        case q"$mods class ${name: TypeName} extends ..${supers: Seq[Tree]}"
             if getFlag(mods, Flag.ABSTRACT) && getFlag(mods, Flag.SEALED) =>
          (mods, name, supers)
        case _ =>
          c.error(c.enclosingPosition, "@root may only be applied to sealed abstract classes.")
          throw new CompileErrorReported()
      }
      
      val cMods = cleanedMods(mods)
      val zName = addZ(name)
      val zpName = addZP(name)
      
      val outCls = q"""
        $cMods class $name extends ..${supers :+ tq"${Builtins.SwivelValueSym}"} {
          type RootValue = $name
          type RootZipper = $zName
          type RootZipperParent = $zpName
          type Zipper <: $zName
        }
        """
      
      val (replacementSuper, replacementDefs) = buildReplacementSupport(mods)
      val (transformSuper, transformDefs) = buildTransformSupportNonleaf(mods)
      val zCls = q"""
        $cMods class $zName extends ${Builtins.ZipperSym} with ..$replacementSuper with ..$transformSuper {
          type RootValue = $name
          type RootZipper = $zName
          type RootZipperParent = $zpName
          type Value <: $name
          type SelfType <: $zName
          
          ..$replacementDefs
          ..$transformDefs
        }
        """
      
      val zpCls = q"""
        $cMods class $zpName extends ${Builtins.ZipperParentSym} {
          type RootValue = $name
          type RootZipper = $zName
          type RootZipperParent = $zpName
          type Value <: $name
        }
        """
      
      val zObj = q"""
        object ${zName.toTermName} {
          @inline
          def $castName(v: ${name}#RootValue): ${name} = v
          @inline
          def $checkSubtreeName(v: ${name}#RootValue): Unit = () 
        }"""
      
      val outputs = Seq(outCls, zCls, zpCls, zObj) ++ inObj
      
      q"""..$outputs"""
    } catch {
      case _: CompileErrorReported =>
        extractExpandees(annottees.toList)
    }
    
  }
  
  def branch(annottees: Tree*): Tree = {
    try {
      val (inCls, inObj) = extractClassAndObject("branch", annottees)
      
      val (mods, name, supers) = inCls match {
        case q"$mods class ${name: TypeName} extends ..${supers: Seq[Tree]}"
             if getFlag(mods, Flag.ABSTRACT) && getFlag(mods, Flag.SEALED) =>
          (mods, name, supers)
        case _ =>
          c.error(c.enclosingPosition, "@branch may only be applied to sealed abstract classes.")
          throw new CompileErrorReported()
      }
      
      val cMods = cleanedMods(mods)
      val directSuper = extractSwivelDirectSuperClassName(supers)
      val zDirectSuper = addZ(directSuper)
      val zpDirectSuper = addZP(directSuper)
      val zName = addZ(name)
      val zpName = addZP(name)
      
      val outCls = q"""
        $cMods class $name extends ..${supers :+ tq"${Builtins.SwivelValueSym}"} {
          type Zipper <: $zName
        }
        """
          
      val (replacementSuper, replacementDefs) = buildReplacementSupport(mods)
      val (transformSuper, transformDefs) = buildTransformSupportNonleaf(mods)
      val zCls = q"""
        $cMods class $zName extends $zDirectSuper with ..$replacementSuper with ..$transformSuper {
          type Value <: $name
          type SelfType <: $zName
          
          ..$replacementDefs
          ..$transformDefs
        }"""
      
      val zpCls = q"""
        $cMods class $zpName extends $zpDirectSuper {
          type Value <: $name
        }
        """
      
      val zObj = q"""
        object ${zName.toTermName} {
          ..${buildCastMethods(name)}
        }"""
      
      val outputs = Seq(outCls, zCls, zpCls, zObj) ++ inObj
      
      q"""..$outputs"""
    } catch {
      case _: CompileErrorReported =>
        extractExpandees(annottees.toList)
    }
  }
  
  def leaf(annottees: Tree*): Tree = {
    try {
      val (inCls, inObj) = extractClassAndObject("leaf", annottees)
      
      val (mods, vName, allArgs, supers, defs) = inCls match {
        case q"${mods: Modifiers} class ${name: TypeName}(..${args: Seq[Tree]}) extends ..${supers: Seq[Tree]} { ..${defs: Seq[Tree]} }" 
             if getFlag(mods, Flag.FINAL) && !getFlag(mods, Flag.TRAIT) =>
          (mods, name, args, supers, defs)
        case _ =>
          c.error(c.enclosingPosition, "@leaf may only be applied to final classes.")
          throw new CompileErrorReported()          
      }

      def newValue(args: Seq[Tree]) = {
        q"new ${vName}(..$args)"
      }

      val cMods = cleanedMods(mods)
      val directSuper = extractSwivelDirectSuperClassName(supers)
      val zDirectSuper = addZ(directSuper)
      val zpDirectSuper = addZP(directSuper)
      val zName = addZ(vName)
      val zpNameBase = addZP(vName)

      def parentArg = q"val $parentName: Option[$directSuper#RootZipperParent]"
      def parent = Ident(parentArg.name)
      def indexArg = q"protected val $indexName: Int"
      def index = Ident(indexArg.name)
      def underlyingArg = q"val value: $vName"
      def underlying = Ident(underlyingArg.name)

      var allArgHandlers: Seq[ArgumentHandler] = null        

      abstract class ArgumentHandler {
        val originalArg: ValDef
        
        checkFieldName(originalArg)
        
        val isSubtree: Boolean = BasicTreeMacros.this.isSubtree(originalArg)
        
        def name: TermName = originalArg.name
        //val rawValueName: TermName = c.freshName(name)
        val zpName: TypeName = addSubtreeName(zpNameBase, name)
        
        def accessorType: Tree = originalArg.tpt
        def accessorExpr: Tree = q"$name"
        def contributeToSubtrees(o: Tree): Tree
        //def arg: DefTree = originalArg
        
        def accessorTypeZ: Tree
        def accessorExprZ: Tree        
        def contributeToSubtreesZ(o: Tree): Tree
        def rawAccessorExprZ: Tree = q"$underlying.$name"
        def accessorDefZ: DefTree
        def copyArgZ: ValDef = ValDef(Modifiers(Flag.PARAM), name, accessorType, rawAccessorExprZ).originalArgPos()

        val transformSubFunction: TermName = TermName("on" + elementType.name.toString())
        
        def elementType: Ident
        def elementTypeZ: Ident = Ident(addZ(elementType.name.toTypeName))
        def elementCompanionZ: Ident = Ident(elementTypeZ.name.toTermName)

        def argZP: ValDef = ValDef(Modifiers(Flag.PARAM), name, accessorType, EmptyTree).originalArgPos()
        def accessorExprZP: Tree
        def zpDefinition: Option[ClassDef]

        lazy val transOrigName = c.freshName(name)
        def transIntermediateName: TermName
        def transOrigDefinition = Some(q"""val $transOrigName = ${accessorExprZ}""")
        def transIntermediateDefinition: Option[Tree]

        
        protected implicit class TreeAdds[T <: Tree](val t: T) {
          def originalArgPos(): t.type = c.internal.setPos(t, originalArg.pos)
        }
      }
      
      case class NonsubtreeArgument(originalArg: ValDef) extends ArgumentHandler {        
        //require(!isSubtree)
        
        def accessorTypeZ: Tree = accessorType
        def accessorExprZ: Tree = rawAccessorExprZ
        def accessorDefZ: DefTree = q"def $name: $accessorTypeZ = $accessorExprZ".originalArgPos()

        def elementType: Ident = Ident(TypeName("Nothing"))

        def accessorExprZP: Tree = accessorExpr
        def zpDefinition: Option[ClassDef] = None

        def contributeToSubtrees(o: Tree): Tree = o
        def contributeToSubtreesZ(o: Tree): Tree = o

        lazy val transIntermediateName = transOrigName
        def transIntermediateDefinition = None
      }
      
      abstract class SubtreeArgument extends ArgumentHandler {
        def accessorExprZ: Tree = q"this.$name"

        def zpReconstructSelfArg: Tree
        def zpToStringSelfCode: Seq[Tree]
        def zpArgs: Seq[ValDef]

        def zpReconstructValueArgs = allArgHandlers.map { h =>
          if (name == h.name) {
            zpReconstructSelfArg
          } else {
            h.accessorExprZP
          }            
        }
        def zpToStringCode = {
          val argsToString: Seq[Tree] = q"${s"${zName.toString()}("}" +:
            allArgHandlers.map({
              case h if h.name == name => q"${","}" +: zpToStringSelfCode
              case h => Seq(q"${","}", h.accessorExprZP)
            }).flatten.drop(1) :+ q"${")"}" :+ q"this.$parentStringName"
          argsToString.reduceLeft((a, b) => q"$a + $b")
        }
        
        def zpDefinition: Option[ClassDef] = {
          // TODO: Make this not a case class and add custom generated hashCode and equals.
          Some(
          q"""
            ${addFinal(cMods)} class $zpName(..$zpArgs, $parentArg) extends $zpDirectSuper {
              type Value = $vName
            
              def $putName(v: RootValue): $zName = new $zName(${newValue(zpReconstructValueArgs)}, $parent)
              def $checkSubtreeName(v: RootValue): Unit = $elementCompanionZ.$checkSubtreeName(v)
              override def toString(): String = {
                $zpToStringCode
              }
            }
            """.originalArgPos())
        }

      
        lazy val transIntermediateName = c.freshName(name)
        def transComputeSingle(v: Tree) = q"""
          { 
            val modified1Value = $v.transformChildren(f)
            val modified1 = modified1Value.toZipper($v.$parentName)
            val modified2 = if (f.${transformSubFunction}.isDefinedAt(modified1)) {
              f.${transformSubFunction}(modified1)
            } else {
              modified1Value
            }
            val vValue = $v.value
            if ((vValue eq modified2) || (vValue == modified2)) {
              vValue
            } else {
              changed = true
              modified2
            }
          }""".originalArgPos()
        def transComputation: Tree
        def transIntermediateDefinition: Option[Tree] = Some(q"val $transIntermediateName = $transComputation".originalArgPos()) 
      }

      case class ScalarArgument(originalArg: ValDef, elementType: Ident) extends SubtreeArgument {
        require(isSubtree)
        
        def accessorTypeZ: Tree = Ident(addZ(elementType.name.toTypeName))
        def accessorDefZ: DefTree = {
          val zpConstrArgs = allArgHandlers.filterNot(_.name == name).map(_.rawAccessorExprZ)
          q"""def $name: $accessorTypeZ = $rawAccessorExprZ.toZipper(Some(new $zpName(..$zpConstrArgs, $parent)))""".originalArgPos()
        }
        
        def contributeToSubtrees(o: Tree): Tree = q"$o :+ $accessorExpr".originalArgPos()
        def contributeToSubtreesZ(o: Tree): Tree = q"$o :+ $accessorExprZ".originalArgPos()

        def zpArgs = {
          allArgHandlers.filter(_.name != name).map(_.argZP)
        }
        def zpReconstructSelfArg = q"$elementCompanionZ.$castName(v)"
        def zpToStringSelfCode = Seq(q"${"[]"}")
        def accessorExprZP: Tree = q"$name"

        def transComputation: Tree = transComputeSingle(Ident(transOrigName))
      }
      
      case class SeqArgument(originalArg: ValDef, containerType: Tree, elementType: Ident) extends SubtreeArgument {
        require(isSubtree)
        
        def accessorTypeZ: Tree = tq"$containerType[$elementTypeZ]"
        def accessorDefZ: DefTree = {
          val zpConstrArgs = allArgHandlers.map(h => {
             if(name != h.name) {
               h.rawAccessorExprZ
             } else {
               q"${rawAccessorExprZ}.updated(i, null)".originalArgPos()
             }
           })
          q"""
            val $name: $accessorTypeZ = $rawAccessorExprZ.view.zipWithIndex.map({ p =>
              val f = p._1
              val i = p._2
              f.toZipper(Some(new $zpName(..$zpConstrArgs, i, $parent)))
            })
            """.originalArgPos()
        }

        def contributeToSubtrees(o: Tree): Tree = q"$o ++ $accessorExpr".originalArgPos()
        def contributeToSubtreesZ(o: Tree): Tree = q"$o ++ $accessorExprZ".originalArgPos()

        def zpArgs = {
          allArgHandlers.map(_.argZP) :+ indexArg
        }
        def zpReconstructSelfArg = q"$accessorExprZP.updated($index, $elementCompanionZ.$castName(v))".originalArgPos()
        //             println((getClass(), v)) 
        def zpToStringSelfCode = Seq(q"""
          $accessorExprZP.updated($index, "[]").map({ v =>
            v.toString()
          }).toString()""".originalArgPos())
        def accessorExprZP: Tree = q"$name"

        override def transOrigDefinition = Some(q"""val $transOrigName = ${accessorExprZ}.view.force""".originalArgPos())
        def transComputation: Tree = q"$transOrigName.map(v => ${transComputeSingle(q"v")})".originalArgPos()
      }
      
      case class OptionArgument(originalArg: ValDef, containerType: Tree, elementType: Ident) extends SubtreeArgument {
        require(isSubtree)
        
        def accessorTypeZ: Tree = tq"$containerType[$elementTypeZ]"
        def accessorDefZ: DefTree = {
          val zpConstrArgs = allArgHandlers.filterNot(_.name == name).map(_.rawAccessorExprZ)
          q"""def $name: $accessorTypeZ = $rawAccessorExprZ.map(_.toZipper(Some(new $zpName(..$zpConstrArgs, $parent))))""".originalArgPos()
        }
        
        def contributeToSubtrees(o: Tree): Tree = q"$o ++ $accessorExpr".originalArgPos()
        def contributeToSubtreesZ(o: Tree): Tree = q"$o ++ $accessorExprZ".originalArgPos()

        def zpArgs = {
          allArgHandlers.filter(_.name != name).map(_.argZP)
        }
        def zpReconstructSelfArg = q"Some($elementCompanionZ.$castName(v))"
        def zpToStringSelfCode = Seq(q"${"[]"}")
        def accessorExprZP: Tree = q"$name"

        def transComputation: Tree = q"$transOrigName.map(v => ${transComputeSingle(q"v")})".originalArgPos()
      }
      
      case class MapArgument(originalArg: ValDef, containerType: Tree, keyType: Tree, elementType: Ident) extends SubtreeArgument {
        require(isSubtree)
        
        def accessorTypeZ: Tree = tq"$containerType[$keyType, $elementTypeZ]"
        def accessorDefZ: DefTree = {
          val zpConstrArgs = allArgHandlers.map(h => {
             if(name != h.name) {
               h.rawAccessorExprZ
             } else {
               q"${h.rawAccessorExprZ} - k".originalArgPos()
             }
           })
          q"""
            val $name: $accessorTypeZ = $rawAccessorExprZ.map({ p =>
              val k = p._1
              val v = p._2
              (k, v.toZipper(Some(new $zpName(..$zpConstrArgs, k, $parent))))
            })
            """.originalArgPos()
        }

        def contributeToSubtrees(o: Tree): Tree = q"$o ++ $accessorExpr.values".originalArgPos()
        def contributeToSubtreesZ(o: Tree): Tree = q"$o ++ $accessorExprZ.values".originalArgPos()

        def keyArg = q"protected val $keyName: $keyType".originalArgPos()
        def key = keyArg.name
        def zpArgs = {
          allArgHandlers.map(_.argZP) :+ keyArg
        }
        def zpReconstructSelfArg = q"$accessorExprZP.updated($key, $elementCompanionZ.$castName(v))".originalArgPos()
        def zpToStringSelfCode = Seq(q"""$accessorExprZP.mapValues(_.toString()).updated($key, "[]").toString()""".originalArgPos())
        def accessorExprZP: Tree = q"$name"
        
        override def transOrigDefinition = Some(q"""val $transOrigName = ${accessorExprZ}""".originalArgPos())
        def transComputation: Tree = q"$transOrigName.mapValues(v => ${transComputeSingle(q"v")}).view.force".originalArgPos()
      }
            
      object ArgumentHandler {
        def apply(d: ValDef): ArgumentHandler = {
          d.tpt match {
            case tpt if !isSubtree(d) =>
              NonsubtreeArgument(d)
            case typ@Ident(_) =>
              ScalarArgument(d, typ)
            case tq"$prefix[${elemType @ Ident(_)}]" if isSeqRef(prefix) =>
              SeqArgument(d, prefix, elemType)
            case tq"$prefix[${elemType @ Ident(_)}]" if isOptionRef(prefix) =>
              OptionArgument(d, prefix, elemType)
            case tq"$prefix[$keyType, ${valType @ Ident(_)}]" if isMapRef(prefix) =>
              MapArgument(d, prefix, keyType, valType)
            case tpt =>
              c.error(d.pos, "Unsupported field type for @subtree annotation.")
              NonsubtreeArgument(d)
          }
        }
      }
      
      allArgHandlers = allArgs.map(ArgumentHandler(_))     
      
      val outCls = q"""
        ${addFinal(cMods)} class $vName(..$allArgs) extends ..${supers} {
          type Zipper = $zName
          def toZipper(parent: Option[RootZipperParent]): $zName = new $zName(this, parent)
          def subtrees: Seq[RootValue] = ${allArgHandlers.foldRight(q"Seq()")(_ contributeToSubtrees _)}
          
          ..$defs
        }
       """
      
      // TODO: Fix value implementation to use inObj.apply if it exists and similarly for case classes.
      //       This is needed so that the value created goes through normal channels and the real constructor can be
      //       private to force such things as hash-consing.
          
      val (replacementSuper, replacementDefs) = buildReplacementSupport(mods)      
      val hasTransform = hasTransformSupportLeaf(mods)
      
      val transformMethod = if (hasTransform) {
        val transformMethod = q"""
          def transformChildren(f: TranformFunction): $vName = {
            var changed: Boolean = false
            ..${allArgHandlers.flatMap(_.transOrigDefinition)}
            ..${allArgHandlers.flatMap(_.transIntermediateDefinition)}
            if (!changed) {
              this.value
            } else {
              copy(..${allArgHandlers.map(h => h.transIntermediateName)}).value
            }
          }
          """
        Some(transformMethod)
      } else {
        None
      }
      
      val zCls = q"""
        final class $zName($underlyingArg, $parentArg) extends $zDirectSuper with ..$replacementSuper {
          type Value = $vName
          type SelfType = $zName
          
          ..${allArgHandlers.map(_.accessorDefZ)}
          def subtrees: Seq[RootZipper] = ${allArgHandlers.foldRight(q"Seq()")(_ contributeToSubtreesZ _)}
          
          def copy(..${allArgHandlers.map(_.copyArgZ)}): $zName = new $zName($underlying.copy(..${allArgHandlers.map(_.copyArgZ.name)}), ${parentArg.name})
                    
          ..$replacementDefs
          ..${transformMethod.toSeq}
        }
        """

      val zClsParents = allArgHandlers.flatMap(_.zpDefinition)
      
      val zTypes = allArgHandlers.map(_.accessorTypeZ)
      val zUnapplyValues = allArgHandlers.map(h => q"v.${h.name}")
      val zObj = q"""
        object ${zName.toTermName} {
          def unapply(v: $zName): Option[(..$zTypes)] = { 
            if (v == null)
              None
            else           
              Some((..$zUnapplyValues))
          }
          ..${buildCastMethods(vName)}
        }
        """
      
      val outputs = Seq(outCls, zCls, zObj) ++ zClsParents ++ inObj
      
      q"""..$outputs"""
    } catch {
      case _: CompileErrorReported =>
        extractExpandees(annottees.toList)
    }
  }
}