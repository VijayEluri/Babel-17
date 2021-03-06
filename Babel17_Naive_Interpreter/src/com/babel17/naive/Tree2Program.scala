package com.babel17.naive

import Program._
import com.babel17.syntaxtree.patterns._
import com.babel17.syntaxtree._
import com.babel17.interpreter.parser._
import scala.collection.immutable.SortedSet
import scala.collection.immutable.SortedMap

class Tree2Program extends ErrorProducer {

  def toList(_nl : NodeList) : List[Node] = {
    var l = List[Node]()
    var nl = _nl
    while (!nl.empty) {
      val m = nl.head
      l = m::l
      nl = nl.tail
    }
    return l.reverse
  }

  def buildStatement (node : Node) : Statement = {
    val s = build(node) match {
      case b : Block => SBlock(b)
      case s : Statement => s
      case e : Expression => SYield (e)
      case se : SimpleExpression => SYield (ESimple (se))
      case id : Id => SYield (ESimple(SEId(id)))
      case x => throwInternalError(node.location(), "buildStatement: "+x)
    }
    s.setLocation(node.location())
    return s
  }

  def buildExpression (node : Node) : Expression =  {
    val e = build(node) match {
      case b : Block => EBlock(b)
      case SBlock(b) => EBlock(b)
      case s : Statement => EBlock(Block(List(s)))
      case e : Expression => e
      case se : SimpleExpression => ESimple(se)
      case id : Id => ESimple(SEId(id))
      case x => throwInternalError(node.location(), "buildExpression: "+x)
    }
    e.setLocation(node.location())
    return e
  }

  def buildBlock (node : Node) : Block = {
    val e = build(node) match {
      case b: Block => b
      case SBlock(b) => b
      case s : Statement => Block(List(s))
      case EBlock(b) => b
      case x => throwInternalError(node.location(), "buildBlock: "+x)
    }
    e.setLocation(node.location())
    return e
  }

  def buildSimpleExpression (node : Node) : SimpleExpression =  {
    val se = build(node) match {
      case b : Block => SEExpr(EBlock(b))
      case SBlock(b) => SEExpr(EBlock(b))
      case s : Statement => SEExpr(EBlock(Block(List(s))))
      case ESimple(se) => se
      case e : Expression => SEExpr(e)
      case se : SimpleExpression => se
      case id : Id => SEId(id)
      case x => throwInternalError(node.location(), "buildSimpleExpression: "+x)
    }
    se.setLocation(node.location())
    return se
  }
  
  def buildModifier(left : Boolean, op : OperatorNode) : SimpleExpression = {
    val loc = op.location
    val x = Id("x")
    x.location = loc
    val y = Id("y")
    y.location = loc
    val sx = SEId(x)
    sx.location = loc
    val sy = SEId(y)
    sy.location = loc
    def mk(m : String) : SimpleExpression = {
      val msg = Id(m.toLowerCase)
      msg.location = loc
      SEApply(SEMessageSend(sx, msg), sy)
    }
    import OperatorNode._
    val body = 
    op.operator match {
      case PLUS => mk(Values.MESSAGE_PLUS)
      case MINUS => mk(Values.MESSAGE_MINUS)
      case TIMES => mk(Values.MESSAGE_TIMES)
      case QUOTIENT => mk(Values.MESSAGE_SLASH)
      case DIV => mk(Values.MESSAGE_DIV)
      case MOD => mk(Values.MESSAGE_MOD)
      case POW => mk(Values.MESSAGE_POW)
      case PLUSPLUS => mk(Values.MESSAGE_PLUSPLUS)
      case MINUSMINUS => mk(Values.MESSAGE_MINUSMINUS)
      case TIMESTIMES => mk(Values.MESSAGE_TIMESTIMES)
      case QUOTIENTQUOTIENT => mk(Values.MESSAGE_SLASHSLASH)
      case OR => SEOr(sx, sy)
      case AND => SEAnd(sx, sy)
      case XOR => SEXor(sx, sy)
      case MIN => SEMin(SEVector(List(sx, sy)))
      case MAX => SEMax(SEVector(List(sx, sy)))
    }
    val pat = 
      if (left)
        PVector(List(PId(x), PId(y)), null)
      else
        PVector(List(PId(y), PId(x)), null)        
    val f = SEFun(MemoTypeNone(), List((pat, ESimple(body), TypeNone())))
    f.location = loc
    f
  }

  def buildBinaryOperation(leftNode:Node, rightNode:Node,
                           op : OperatorNode) : SimpleExpression =
  {
    def left() : SimpleExpression = buildSimpleExpression(leftNode)
    def right() : SimpleExpression = buildSimpleExpression(rightNode)
    def mk(m : String) : SimpleExpression = {
      val msg = Id(m.toLowerCase)
      msg.setLocation(op.location)
      SEApply(SEMessageSend(left(), msg), right())
    }
    import OperatorNode._
    op.operator match {
      case PLUS => mk(Values.MESSAGE_PLUS)
      case MINUS => mk(Values.MESSAGE_MINUS)
      case TIMES => mk(Values.MESSAGE_TIMES)
      case QUOTIENT => mk(Values.MESSAGE_SLASH)
      case DIV => mk(Values.MESSAGE_DIV)
      case MOD => mk(Values.MESSAGE_MOD)
      case POW => mk(Values.MESSAGE_POW)
      case PLUSPLUS => mk(Values.MESSAGE_PLUSPLUS)
      case MINUSMINUS => mk(Values.MESSAGE_MINUSMINUS)
      case TIMESTIMES => mk(Values.MESSAGE_TIMESTIMES)
      case QUOTIENTQUOTIENT => mk(Values.MESSAGE_SLASHSLASH)
      case TO => mk(Values.MESSAGE_TO)
      case DOWNTO => mk(Values.MESSAGE_DOWNTO)
      case APPLY => SEApply(left(), right())
      case MESSAGE_SEND =>
        build(rightNode) match {
          case msg : Id => SEMessageSend(left(), msg)
          case SELens(_, lens) => SELensSend(left(), lens)
          case x => throwInternalError(op.location, "buildBinaryOperation, no message: "+x)
        }
      case OR => SEOr(left(), right())
      case AND => SEAnd(left(), right())
      case CONS => SECons(left(), right())
      case INTERVAL => SEInterval(left(), right())
      case RELATE => SERelate(left(), right())
      case CONVERT =>
        rightNode match {
          case t: TypeIdNode =>
            SEConvert(left(), Left(buildTypePath(t)))
          case t =>
            SEConvert(left(), Right(right()))
        }
      case LENS =>
        var r : SimpleExpression = null
        left() match {
          case SEId(id) =>
            val se = right()
            Lens.isLensPath(se) match {
              case Some(id2) =>
                if (id2 == id) {
                  r = SELens(id, se)
                } 
              case _ =>
            }
          case _ =>
        }
        if (r == null) {
          error(rightNode.location, "invalid lens expression")
          right()
        }
        else r
      case FUNCTIONS_LENS =>
        SEDirectLens(left(), right())
        
      case x => throwInternalError(op.location, "buildBinaryOperation: "+x)
    }
  }

  def buildTypePath(n : Node) : Path = {
    val tn : TypeIdNode = n.asInstanceOf[TypeIdNode]
    val ids = toList(tn.ids).map(x => build(x).asInstanceOf[Id])
    val p = Path(ids)
    p.setLocation(n.location)
    p
  }


  def buildType(n : Node) : Type = {
    val p = buildTypePath(n)
    val t = TypeSome(p)
    t.setLocation(n.location)
    t
  }

  def buildConstr(n : ConstrNode) : SimpleExpression = {
    val c = Constr(n.name.toUpperCase)
    c.setLocation(n.nameLoc)
    val param = if (n.arg == null) SERecord(List()) else buildSimpleExpression(n.arg)
    SEConstr(c, param)
  }

  def buildCompare(n : CompareNode) : SimpleExpression = {
    def opconv(op : OperatorNode) : CompareOp = {
      val p = op.operator match {
        case OperatorNode.EQUAL => EQUAL()
        case OperatorNode.UNEQUAL => UNEQUAL()
        case OperatorNode.GREATER => GREATER()
        case OperatorNode.GREATER_EQ => GREATER_EQ()
        case OperatorNode.LESS => LESS()
        case OperatorNode.LESS_EQ => LESS_EQ()
        case x => throwInternalError(op.location, "buildCompare.opconv: "+x)
      }
      p.setLocation(op.location)
      p
    }
    var operands : List[SimpleExpression] = List()
    var operators : List[CompareOp] = List()
    var isOperator = false;
    for (i <- toList(n.comparisons)) {
      if (isOperator)
        operators = opconv(i.asInstanceOf[OperatorNode])::operators
      else
        operands = buildSimpleExpression(i)::operands
      isOperator = !isOperator
    }
    SECompare(operands.reverse, operators.reverse)
  }

  def buildNullary(n : NullaryNode) : Locatable = {
    import OperatorNode._
    val result : Locatable = n.operator().operator match {
      case TRUE => SEBool(true)
      case FALSE => SEBool(false)
      case THIS => SEThis()
      case ROOT => SERoot()
      case k => throwInternalError(n.location, "unknown nullary operator code: "+k)
    }
    result.setLocation(n.location)
    result
  }

  def buildUnary(n : UnaryNode) : Locatable = {
    val arg = buildSimpleExpression(n.operand)
    def mk(m : String) : SimpleExpression = {
      val msg = Id(m.toLowerCase)
      msg.setLocation(n.operator().location)
      SEMessageSend(arg, msg)
    }
    def attachSTE(se : SimpleExpression, name : String) : SimpleExpression = {
      se.stackTraceElement = Values.StackTraceElement(n.location, name+" expression")
      se
    }
    import OperatorNode._
    val result : Locatable = n.operator().operator match {
      case NOT => SENot(arg)
      case UMINUS => mk(Values.MESSAGE_UMINUS)
      case LAZY => SELazy(attachSTE(arg, "lazy"))
      case RANDOM => SERandom(arg)
      case NATIVE => SENative(arg)
      case MIN => SEMin(arg)
      case MAX => SEMax(arg)
      case CONCURRENT => SEConcurrent(attachSTE(arg, "concurrent"))
      case CHOOSE => SEChoose(arg)
      case FORCE => SEForce(attachSTE(arg, "force"))
      case EXCEPTION => attachSTE(SEException(arg), "exception")
      case TYPEOF => SETypeOf(arg)
      case k => throwInternalError(n.location, "unknown unary operator code: "+k)
    }
    result.setLocation(n.location)
    result
  }

  def mergeLoc(l1 : Location, l2 : Location) : Location = {
    if (l1 == null) l2
    else if (l2 == null) l1
    else l1.add(l2)
  }

  def buildIf(n : IfNode) : Locatable = {
    val conds = toList(n.conditions)
    val blocks = toList(n.blocks)
    def mkif (cs : List[Node], bs : List[Node]) : Statement = {
      (cs, bs) match {
        case (c::crest, b::brest) =>
          val cond = buildSimpleExpression(c)
          val yes = buildBlock(b)
          val no = mkif(crest, brest) match {
            case SBlock(b) => b
            case x =>
              val q = Block(List(x))
              q.setLocation(x.location)
              q
          }
          val r = SIf(cond, yes, no)
          r.setLocation(mergeLoc(cond.location, mergeLoc(yes.location, no.location)))
          r
        case (_, List(b)) => buildStatement(b)
        case (List(), List()) => SBlock(Block(List()))
        case _ => throwInternalError(n.location, "invalid if")
      }
    }
    mkif(conds, blocks)
  }

  def mkExpressionBranches(ps : List[Node], bs : List[Node]) : List[(Pattern, Expression, Type)] = {
    (ps, bs) match {
      case (p::prest, b::brest) =>
        (buildProperPattern(p), buildExpression(b), TypeNone()) :: (mkExpressionBranches(prest, brest))
      case (List(), List()) => List()
      case _ => throwInternalError(null, "invalid expression branches")
    }
  }

  def mkBlockBranches(ps : List[Node], bs : List[Node]) : List[(Pattern, Block)] = {
    (ps, bs) match {
      case (p::prest, b::brest) =>
        (buildProperPattern(p), buildBlock(b)) :: (mkBlockBranches(prest, brest))
      case (List(), List()) => List()
      case _ => throwInternalError(null, "invalid block branches")
    }
  }

  def build(node : Node) : Locatable = {
    val result : Locatable = node match {
      case n : BeginNode =>
        SBlock(build(n.block()).asInstanceOf[Block])
      case n : BlockNode =>
        val l = toList(n.statements())
        Block(l.map(buildStatement).toList)
      case n : IntegerNode =>
        SEInt(new BigInt(n.value()))
      case n : FloatNode =>
        SEFloat(new BigInt(n.mantissa()), new BigInt(n.exponent()))
      case n : StringNode =>
        SEString(n.value)
      case n : MessageNode =>
        if (n.name != null)
          Id(n.name().toLowerCase)
        else
          SELens(null, buildSimpleExpression(n.lens)) // hack
      case n : NullaryNode =>
        buildNullary(n)
      case n : BinaryNode =>
        buildBinaryOperation(n.leftOperand, n.rightOperand, n.operator);
      case n : UnaryNode =>
        buildUnary(n)
      case n : IdentifierNode =>
        Id(n.name.toLowerCase)
      case n : ConstrNode =>
        buildConstr(n)
      case n : CompareNode =>
        buildCompare(n)
      case n : ValNode =>
        val e = buildExpression(n.rightSide)
        val p = buildProperPattern(n.pattern)
        if (n.assign) SAssign(p, e) else SVal(p, e)
      case n : LensAssignNode =>
        val l = buildSimpleExpression(n.leftSide)
        val e = buildExpression(n.rightSide)
        Lens.isLensPath(l) match {
          case Some(id) => 
            SLensAssign(id, SELens(id, l), e)
          case None =>
            error(n.leftSide.location, "invalid left hand side of assignment")
            SYield(e)
        }    
      case n : LensModifyNode =>
        val l = buildSimpleExpression(n.leftSide)
        val e = buildExpression(n.rightSide)
        val f = buildModifier(n.left, n.op)
        Lens.isLensPath(l) match {
          case Some(id) => 
            SLensModify(id, SELens(id, l), e,  f)
          case None =>
            error(n.leftSide.location, "invalid left hand side of assignment")
            SYield(e)
        }            
      case n : ImportNode => {
        val nodes = n.ids
        val path = Path(toList(nodes).map(x => build(x).asInstanceOf[Id]))
        path.location = nodes.location
        if (nodes.length < 1)
          error(n.location, "import path must contain at least one dot")
        def mkid(n : Node) = build(n).asInstanceOf[Id]
        val entries = toList(n.entries).map(x => x.asInstanceOf[ImportNode.Entry])
        var plus : List[(Id, Id)] = List()
        for (e <- entries) {
          e.entryType match {
            case ImportNode.ENTRY_MAP =>
              plus = (mkid(e.id1), mkid(e.id2)) :: plus
            case ImportNode.ENTRY_PLUS =>
              val i = mkid(e.id1)
              plus = (i, i) :: plus
            case ImportNode.ENTRY_MINUS =>
              error(e.location, "negative imports are not allowed")
            case ImportNode.ENTRY_ALL =>
              error(e.location, "wildcard imports are not allowed")
          }
        }
        TempImport(path, plus.reverse)
      }
      case n : ModuleNode => {
        val nodes = n.moduleId.ids
        val path = Path(toList(nodes).map(x => build(x).asInstanceOf[Id]))
        path.location = nodes.location
        SModule(path, buildBlock(n.block))
      }
      case n : ForNode =>
        SFor(buildProperPattern(n.pattern), buildSimpleExpression(n.collection),
          buildBlock(n.block))
      case n : IfNode =>
        buildIf(n)
      case n : LambdaNode =>
        val f = SEFun(MemoTypeNone(), mkExpressionBranches(toList(n.patterns), toList(n.blocks)))
        f.stackTraceElement = Values.StackTraceElement(n.location, "application of anonymous function")
        f
      case n : WhileNode =>
        SWhile(buildSimpleExpression(n.condition), buildBlock(n.block))
      case  n : WithNode =>
        EWith(buildSimpleExpression(n.collector), buildBlock(n.control))
      case n : YieldNode =>
        SYield(buildExpression(n.expr))
      case p : PragmaNode =>
        import PragmaNode._
        val e = buildExpression(p.expr)
        val u = p.pragma match {
          case PRAGMA_PRINT => PragmaPrint(e)
          case PRAGMA_LOG => PragmaLog(e)
          case PRAGMA_ASSERT => PragmaAssert(e)
          case PRAGMA_PROFILE => PragmaProfile(e)
          case PRAGMA_CATCH =>
            val pat = buildProperPattern(p.pattern())
            if (isExceptionPattern(pat)) 
              error(pat.location, "superfluous 'exception'")
            PragmaCatch(e, pat)
        }
        SPragma(u)
      case n : MatchNode =>
        SMatch(buildSimpleExpression(n.value),
          mkBlockBranches(toList(n.patterns), toList(n.blocks)))
      case n : TryNode =>
        val s = STry(buildBlock(n.block),
          mkBlockBranches(toList(n.patterns), toList(n.blocks)))
        for ((pat,_) <- s.branches) {
          if (isExceptionPattern(pat)) 
              error(pat.location, "superfluous 'exception'")
        }
        s
      case n : MemoizeNode =>
        def buildMemo(memoNode : Node) : (MemoType, Id) = {
          memoNode match {
            case mid : MemoizeNode.MemoId =>
              val id = Id(mid.id.name.toLowerCase)
              id.setLocation(mid.id.location)
              val m =
                if (mid.strong)
                  MemoTypeStrong()
                else
                  MemoTypeWeak()
              m.setLocation(mid.location)
              (m, id)
          }
        }
        TempMemoize(toList(n.memoIds).map(buildMemo _))
      case n : DefNode =>
        val id = Id(n.id.name.toLowerCase)
        id.setLocation(n.id.location)
        val rightSide = buildExpression(n.rightSide)
        var returnType : Program.Type = TypeNone()
        if (n.returnType != null) returnType = buildType(n.returnType);
        if (n.pattern != null) {
          val pat = buildProperPattern(n.pattern)
          TempDef1(id, pat, rightSide, returnType)
        } else {
          TempDef0(id, rightSide, returnType)
        }
      case n : TypedefNode =>
        def buildClause(_tc : Node) : (Pattern, Option[Expression]) = {
          val tc = _tc.asInstanceOf[TypedefClauseNode]
          val p = buildPattern(tc.pattern)
          var e : Option[Expression] = None
          if (tc.expr != null) e = Some(buildExpression(tc.expr))
          (p, e)
        }
        val id = Id(n.id.name.toLowerCase)
        id.setLocation(n.id.location)
        val clauses = toList(n.clauses).map(buildClause _)
        TempTypeDef(id, clauses)
      case n : ConversionNode =>
        val e = buildExpression(n.expr)
        TempConversionDef(buildTypePath(n.returnType), e, n.automatic)
      case n : TypeExprNode =>
        SETypeExpr(buildTypePath(n.typeId))
      case n : ListNode =>
        val ses = toList(n.elements).map(buildSimpleExpression _)
        if (n.isVector)
          SEVector(ses)
        else
          SEList(ses)
      case n : MapNode =>
        def buildKeyValue(node : Node) : (SimpleExpression, SimpleExpression) = {
          node match {
            case kv : MapNode.KeyValue =>
              val k = buildSimpleExpression(kv.key)
              val v = buildSimpleExpression(kv.value)
              (k, v)
            case _ => throwInternalError(n.location, "invalid MapNode")
          }
        }
        SEMap(toList(n.elements).map(buildKeyValue _))
      case n : SetNode =>
        SESet(toList(n.elements).map(buildSimpleExpression _))
      case n : RecordNode =>
        def buildRecordValue(node : Node) : (Id, SimpleExpression) = {
          node match {
            case kv : RecordNode.MessageValue =>
              val m = Id(kv.message.name.toLowerCase)
              m.setLocation(kv.message.location)
              val v = buildSimpleExpression(kv.value)
              (m, v)
            case _ => throwInternalError(n.location, "invalid RecordNode")
          }
        }
        SERecord(toList(n.elements).map(buildRecordValue _))
      case n : ObjectNode =>
        val block = buildBlock(n.block)
        for (st <- block.statements) {
          if (!isAllowedInObject(st))
            error(st.location, "this statement is not allowed in an object definition")
        }
        //val messages = CollectVars.collectDefIds(block.statements).toList
        if (n.parents != null) {
          val parents = buildSimpleExpression(n.parents)
          if (n.combineMethod == ObjectNode.COMBINE_GLUE)
            SEGlueObj(parents, block, SortedSet())
          else {
            error(n.location, "cannot use * operator for inheritance, must use +");
            SEGlueObj(parents, block, SortedSet())
          }
        } else SEObj(block, SortedSet())
      case n : PrivateNode => {
        def buildVisibility(vNode : Node) : (Visibility, Id) = {
          vNode match {
            case vid : PrivateNode.PrivateId =>
              val id = Id(vid.id.name.toLowerCase)
              id.setLocation(vid.id.location)
              val v = VisibilityNo()
              v.setLocation(vid.location)
              (v, id)
          }
        }
        TempPrivate(toList(n.privateIds).map(buildVisibility _))
      }
      case n : ParseErrorNode =>
        error(node.location(), "syntax error")
        SEVector(List())
      case _ =>
        error(node.location(), "invalid Babel-17 term encountered: "+node)
        Block(List())
    }
    result.setLocation(node.location())
    return result
  }

  def isDeltaPattern(pattern : Pattern) : Boolean = {
    pattern match {
      case PEllipsis() => true
      case PIf (pat, _) => isDeltaPattern(pat)
      case PAs (_, pat) => isDeltaPattern(pat)
      case _ => false
    }
  }
  
  def isExceptionPattern(pattern : Pattern) : Boolean = {
    pattern match {
      case PException(_) => true
      case PIf (pat, _) => isExceptionPattern(pat)
      case PAs (_, pat) => isExceptionPattern(pat)
      case _ => false
    }    
  }

  def splitDelta(list : List[Pattern]) : (List[Pattern], Pattern) = {
    val (u, v) =
      if (list.isEmpty) (list, null)
      else {
        val last = list.last
        if (isDeltaPattern(last)) {
          val len = list.length
          if (len == 1) {
            error(last.getLocation(), "element before delta pattern expected")
            (List(), null)
          } else {
            (list.take(len-1), last)
          }
        } else (list, null)
      }
    u.find(isDeltaPattern) match {
      case Some(delta) =>
        error(delta.getLocation, "misplaced delta-pattern");
        (List(), null)
      case None =>
        (u, v)
    }
  }

  def buildProperPattern(patternNode : Node) : Pattern = {
    val p = buildPattern(patternNode)
    if (isDeltaPattern(p)) {
      error(patternNode.location, "misplaced delta-pattern")
      PAny()
    } else p
  }

  def buildPattern(patternNode : Node) : Pattern = {
    val result : Pattern = patternNode match {
      case p : IdentifierPattern =>
        val id = Id(p.name.toLowerCase)
        id.setLocation(patternNode.location)
        PId(id)
      case p : ConstrPattern =>
        val arg = if (p.pattern == null) PAny() else buildProperPattern(p.pattern)
        val constr = Constr(p.name.toUpperCase)
        constr.setLocation(p.nameLocation)
        PConstr(constr, arg)
      case p : ListPattern =>
        val elems =
          for (i <- toList(p.elements))
          yield buildPattern(i)
        val (e, d) = splitDelta(elems.toList)
        if (p.isVector) PVector(e,d) else PList(e,d)
      case p : SetPattern =>
        val elems =
          for (i <- toList(p.elements))
          yield buildPattern(i)
        val (e, d) = splitDelta(elems.toList)
        PSet(e, d)
      case p : ForPattern =>
        val elems =
          for (i <- toList(p.elements))
          yield buildPattern(i)
        val (e, d) = splitDelta(elems.toList)
        PFor(e, d)
      case p : MapPattern =>
        var elems : List[(Pattern, Pattern)] = List();
        var delta : Pattern = null;
        for (e <- toList(p.elements))
          e match {
            case kv : MapPattern.KeyValue =>
              elems = (buildProperPattern(kv.key), buildProperPattern(kv.value))::elems
            case d : PatternNode =>
              delta = buildPattern(d)
              if (!isDeltaPattern(delta)) {
                error(d.location, "key/value or delta pattern expected");
                delta = null;
              }
            case _ => throwInternalError(e.location, "invalid key/value pattern")
          }
        PMap(elems.reverse, delta)
      case p : RecordPattern =>
        var elems : List[(Id, Pattern)] = List();
        var delta : Pattern = null;
        for (e <- toList(p.elements))
          e match {
            case mv : RecordPattern.MessageValue =>
              val idpat = mv.message
              val m = Id(idpat.name.toLowerCase)
              m.setLocation(idpat.location);
              elems = (m, buildProperPattern(mv.value))::elems
            case d : PatternNode =>
              delta = buildPattern(d)
              if (!isDeltaPattern(delta)) {
                error(d.location, "message/value or delta pattern expected");
                delta = null;
              } else delta match {
                case PEllipsis() =>
                case _ => error(d.location, "only a simple '...' is allowed here")
              }
            case _ => throwInternalError(e.location, "invalid key/value pattern")
          }
        PRecord(elems.reverse, delta != null)
      case p : NullaryPattern =>
        import NullaryPattern._
        p.kind match {
          case ANY => PAny()
          case ELLIPSIS => PEllipsis()
          case TRUE => PBool(true)
          case FALSE => PBool(false)
          case k => throwInternalError(patternNode.location, "invalid kind of NullaryPattern: "+k)
        }
      case p : StringPattern =>
        PString(p.str())
      case p : IntegerPattern =>
        PInt(new BigInt(p.value))
      case p : ValPattern =>
        PVal(buildSimpleExpression(p.value))
      case p : PredicatePattern =>
        if (!p.deconstruct) {
          val pat = if (p.pattern == null) PBool(true) else buildProperPattern(p.pattern)
          val pred = buildSimpleExpression(p.predicate)
          PPredicate(pred, pat)
        } else {
          val pat = if (p.pattern == null) PAny() else buildProperPattern(p.pattern)
          val c = buildSimpleExpression(p.predicate)
          PDestruct(c, pat)
        }
      case p : IfPattern =>
        PIf(buildPattern(p.pattern), buildSimpleExpression(p.condition))
      case p : AsPattern =>
        val id = Id(p.identifier.name.toLowerCase)
        id.setLocation(p.identifier.location)
        PAs(id, buildPattern(p.pattern))
      case p : ConsPattern =>
        PCons(buildProperPattern(p.head), buildProperPattern(p.tail))
      case p : ExceptionPattern =>
        PException(buildProperPattern(p.param))
      case p : TypePattern =>
        val typeIdNode : TypeIdNode = p.typeId
        if (typeIdNode != null) {
          PType(buildProperPattern(p.pattern), buildType(typeIdNode))
        } else {
          PTypeVal(buildProperPattern(p.pattern), buildSimpleExpression(p.typeValue))
        }
      case p : InnerValuePattern =>
        val id = build(p.typeId).asInstanceOf[Id]
        PInnerValue(Path(List()).append(id), buildProperPattern(p.pattern))
      case p : ParseErrorNode =>
        error(patternNode.location(), "pattern syntax error")
        PAny()
      case _ =>
        error(patternNode.location(), "invalid Babel-17 pattern encountered: "+patternNode)
        PAny()
    }
    result.setLocation(patternNode.location)
    result
  }

  def makeProgram(result : Parser.ParseResult) : Term = {
    val node = result.node
    if (node != null)
      build(node).asInstanceOf[Term]
    else
      Block(List())
  }

  /*def buildProgram(moduleSystem : ModuleSystem, errors : List[ErrorMessage],
                   result : Parser.ParseResult) : (Term, List[ErrorMessage]) = {
    val term = makeProgram(result)
    val mds = ModuleSystem.scanForModules(term)
    moduleSystem.source = source
    for (md <- mds) {
      moduleSystem.add(md)
    }
    val rt = new RemoveTemporaries(moduleSystem)
    rt.source = source
    val rterm = rt.transform(rt.emptyModuleEnv, term)
    val linearScope = new LinearScope(moduleSystem)
    linearScope.source = source
    linearScope.check(linearScope.emptyEnv, rterm)
    val es = errors ++ Errors.fromParseResult(result) ++
      moduleSystem.errors ++ rt.errors ++ linearScope.errors
    (rterm, es)
  }*/

}
