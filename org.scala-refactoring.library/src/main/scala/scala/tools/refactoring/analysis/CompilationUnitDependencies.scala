/*
 * Copyright 2005-2010 LAMP/EPFL
 */

package scala.tools.refactoring
package analysis

trait CompilationUnitDependencies {
  // we need to interactive compiler because we work with RangePositions
  this: common.InteractiveScalaCompiler with common.TreeTraverser with common.TreeExtractors =>

  import global._

  /**
   * Calculates a list of all needed imports for the given Tree.
   */
  def neededImports(t: Tree): List[Select] = {

        
    /**
     * Helper function to filter out trees that we don't need
     * to import, for example because they come from Predef.
     */
    def isImportReallyNeeded(t: Select) = {
      
      def checkIfQualifierIsNotDefaultImported = {
        val Scala = newTypeName("scala")
        t.qualifier match {
          case Ident(names.scala) => false
          case This(Scala) => false
          case Select(Ident(names.scala), names.pkg) => false
          case Select(Ident(names.scala), names.Predef) => false
          case Select(This(Scala), names.Predef) => false
          case qual if qual.symbol.isSynthetic && !qual.symbol.isModule => false
          case _ => true
        }
      }
      
      val lastSymbol = t.filter(_ => true).last.symbol
      
      if(lastSymbol != NoSymbol && lastSymbol.isLocal) {
        // Our import "chain" starts from a local value,
        // so we cannot import `t` globally.
        false
      } else {
        checkIfQualifierIsNotDefaultImported
      }
    }
    
    /**
     * Finds the last "visible" (with a range position) select in some tree selection.
     * 
     * Selects are usually only partly written down in source code (except when we write
     * down the full name to some identifier), so there exists a select at which the tree
     * turns from being visible to being invisible. We need to find this tree to determine
     * whether we need do make an import with the minimally required path.
     * 
     * This function also already filters trees that we don't need to import, e.g. from the
     * Predef or the scala package.
     */
    def findDeepestNeededSelect(t: Tree): Option[Select] = t match {
      case selected @ Select(qual @ Select(underlying, _), _) if qual.symbol.isPackageObject && underlying.pos.isRange =>
        
        /* When importing from a package object, e.g. scala.sys.`package`.error, the `package` select
         * doesn't have a position. So we "skip" this package object and continue with the underlying
         * select, which might again reveal a range position. 
         * 
         * If we find out that we need that underlying select, we return the original selected tree on
         * the package object.
         * */
        findDeepestNeededSelect(underlying) map (_ => selected)
        
      case s @ Select(qual, name) if s.pos.isRange && !qual.pos.isRange =>
        Some(s) filter isImportReallyNeeded
      case s: Select =>
        findDeepestNeededSelect(s.qualifier)
      case _ =>
        None
    }
    
    val neededDependencies = dependencies(t).flatMap {
      case t: Select if !t.pos.isRange => Some(t) filter isImportReallyNeeded
      case t => findDeepestNeededSelect(t)
    }.distinct
    
    /**
     * Converts a tree containing Idents and Selects to a `.` separated string.
     */
    def asString(t: Tree) = {
      t.filter(_ => true).map {
        case Ident(name) => name.toString
        case Select(_, name) => name.toString
        case _ => ""
      }.reverse.mkString(".")
    }
    
    // Eliminate duplicates by converting them to strings.
    neededDependencies.groupBy(asString).map(_._2.head).toList
  }

  /**
   * Calculates all the external dependencies the given Tree has.
   * Compared to `neededImports`, this function might also return
   * trees that don't need to be explicitly imported, for example
   * because they are defined in the same compilation unit.
   */
  def dependencies(t: Tree): List[Select] = {

    val result = new collection.mutable.HashMap[String, Select]
    
    def addToResult(t1: Select) = {
      val key = t1.toString      
      result.get(key) match {
        case None =>
          result += (key -> t1)
        case Some(t2) =>
          val lengthOfVisibleQualifiers1 = t1.filter(_.pos.isRange)
          val lengthOfVisibleQualifiers2 = t2.filter(_.pos.isRange)
          
          if(lengthOfVisibleQualifiers1.size < lengthOfVisibleQualifiers2.size) {
            // If we have an imported type that is used with the full package
            // name but also with the imported name, we keep the one without the
            // package so we don't incorrectly remove the import.
            result += (key -> t1)
          }
      }
    }

    val traverser = new TraverserWithFakedTrees {
      
      def isSelectFromInvisibleThis(t: Tree) = t.exists {
        case t: This => !t.pos.isRange
        case _ => false
      }
      
      def foundPotentialTree(t: Tree) = {
        t match {
          case Select(Ident(name), _) if name startsWith nme.EVIDENCE_PARAM_PREFIX => 
            ()
          case t: Select => 
            addToResult(t)
          case _ =>
            ()
        }
      }
      
      def handleSelectFromImplicit(t: Tree) = {
        val selects = t.find {
          case t: Select => 
            !isSelectFromInvisibleThis(t)
          case _ => false
        } 
        selects foreach foundPotentialTree
      }
      
      override def traverse(root: Tree) = root match {

        case Import(_, _) => ()

        case Select(Ident(names.scala), _) => ()
        
        case Select(Select(Ident(names.scala), names.pkg), _) => ()
        
        case t : ApplyImplicitView =>
          
          // if we find a select, it's a dependency
          // this is likely not fine-grained enough
          // and might add too many dependencies
          t.fun find {
            case t: Select => true
            case _ => false
          } foreach {
            case t: Select => foundPotentialTree(t)
          }
          
          t.args foreach traverse
          
        case t : ApplyToImplicitArgs =>
          traverse(t.fun)
          t.args foreach handleSelectFromImplicit
        
        case Select(New(qual), _) =>
          traverse(qual)
        
        case t @ Select(qual, _) if t.pos.isRange =>
            
          // we don't need to add a dependency for method calls where the receiver
          // is explicit in the source code.
          val isMethodCallFromExplicitReceiver = qual.pos.isRange && t.symbol.isMethod
          
          if (!isMethodCallFromExplicitReceiver && !isSelectFromInvisibleThis(qual)) {
            addToResult(t)
          }

          super.traverse(t)

        case _ => super.traverse(root)
      }
    }

    traverser.traverse(t)
    
    val deps = result.values.toList

    deps.filterNot(_.symbol.isPackage).toList
  }
}
