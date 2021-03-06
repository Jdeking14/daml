// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.lf
package transaction

import com.daml.lf.EitherAssertions
import com.daml.lf.data.ImmArray
import com.daml.lf.data.Ref.{Identifier, PackageId, Party, QualifiedName}
import com.daml.lf.transaction.Node.{GenNode, NodeCreate, NodeExercises, NodeFetch}
import com.daml.lf.transaction.{Transaction => Tx, TransactionOuterClass => proto}
import com.daml.lf.value.Value.{ContractInst, ValueParty, VersionedValue}
import com.daml.lf.value.ValueCoder.{DecodeError, EncodeError}
import com.daml.lf.value.{Value, ValueCoder, ValueVersion, ValueVersions}
import com.daml.lf.transaction.TransactionVersions._
import com.daml.lf.transaction.VersionTimeline.Implicits._
import org.scalatest.prop.PropertyChecks
import org.scalatest.{Inside, Matchers, WordSpec}

import scala.collection.immutable.HashMap
import scala.collection.JavaConverters._

class TransactionCoderSpec
    extends WordSpec
    with Matchers
    with Inside
    with EitherAssertions
    with PropertyChecks {

  import com.daml.lf.value.test.ValueGenerators._

  private[this] val defaultTransactionVersion = TransactionVersions.acceptedVersions.lastOption getOrElse sys
    .error("there are no allowed versions! impossible! but could it be?")

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 1000, sizeRange = 10)

  "encode-decode" should {
    "do contractInstance" in {
      forAll(contractInstanceGen) { coinst: ContractInst[Tx.Value[Value.ContractId]] =>
        Right(coinst) shouldEqual TransactionCoder.decodeContractInstance(
          ValueCoder.CidDecoder,
          TransactionCoder.encodeContractInstance(ValueCoder.CidEncoder, coinst).toOption.get,
        )
      }
    }

    "do NodeCreate" in {
      forAll(malformedCreateNodeGen, valueVersionGen()) {
        (node: NodeCreate[Value.ContractId, Tx.Value[Value.ContractId]], _: ValueVersion) =>
          val encodedNode = TransactionCoder
            .encodeNode(
              TransactionCoder.NidEncoder,
              ValueCoder.CidEncoder,
              defaultTransactionVersion,
              NodeId(0),
              node,
            )
            .toOption
            .get
          Right((NodeId(0), node)) shouldEqual TransactionCoder.decodeNode(
            TransactionCoder.NidDecoder,
            ValueCoder.CidDecoder,
            defaultTransactionVersion,
            encodedNode,
          )

          Right(node.informeesOfNode) shouldEqual
            TransactionCoder
              .protoNodeInfo(defaultTransactionVersion, encodedNode)
              .map(_.informeesOfNode)
      }
    }

    "do NodeFetch" in {
      forAll(fetchNodeGen, valueVersionGen()) {
        (node: NodeFetch.WithTxValue[Value.ContractId], _: ValueVersion) =>
          val encodedNode =
            TransactionCoder
              .encodeNode(
                TransactionCoder.NidEncoder,
                ValueCoder.CidEncoder,
                defaultTransactionVersion,
                NodeId(0),
                node,
              )
              .toOption
              .get
          Right((NodeId(0), withoutByKeyFlag(node))) shouldEqual TransactionCoder.decodeNode(
            TransactionCoder.NidDecoder,
            ValueCoder.CidDecoder,
            defaultTransactionVersion,
            encodedNode,
          )
          Right(node.informeesOfNode) shouldEqual
            TransactionCoder
              .protoNodeInfo(defaultTransactionVersion, encodedNode)
              .map(_.informeesOfNode)
      }
    }

    "do NodeExercises" in {
      forAll(danglingRefExerciseNodeGen) {
        node: NodeExercises[NodeId, Value.ContractId, Tx.Value[Value.ContractId]] =>
          val encodedNode =
            TransactionCoder
              .encodeNode(
                TransactionCoder.NidEncoder,
                ValueCoder.CidEncoder,
                defaultTransactionVersion,
                NodeId(0),
                node,
              )
              .toOption
              .get
          Right((NodeId(0), withoutByKeyFlag(node))) shouldEqual TransactionCoder.decodeNode(
            TransactionCoder.NidDecoder,
            ValueCoder.CidDecoder,
            defaultTransactionVersion,
            encodedNode,
          )

          Right(node.informeesOfNode) shouldEqual
            TransactionCoder
              .protoNodeInfo(defaultTransactionVersion, encodedNode)
              .map(_.informeesOfNode)
      }
    }

    "do transactions with default versions" in {
      forAll(noDanglingRefGenTransaction, minSuccessful(50)) { genT_ =>
        val genT = genT_.copy(nodes = genT_.nodes.transform((_, n) => withoutByKeyFlag(n)))
        val t = TransactionVersions.assertAsVersionedTransaction(genT)
        val encodedTx: proto.Transaction =
          assertRight(
            TransactionCoder
              .encodeTransaction(TransactionCoder.NidEncoder, ValueCoder.CidEncoder, t),
          )

        val decodedVersionedTx: VersionedTransaction[NodeId, Value.ContractId] =
          assertRight(
            TransactionCoder
              .decodeTransaction(
                TransactionCoder.NidDecoder,
                ValueCoder.CidDecoder,
                encodedTx,
              ),
          )

        decodedVersionedTx shouldEqual t

        // Verify that we can compute the informees for all nodes, for both
        // the deserialized and serialized form.
        val encodedNodes = encodedTx.getNodesList.asScala.map(n => n.getNodeId -> n).toMap
        decodedVersionedTx.transaction.nodes.foreach {
          case (nodeId, node) =>
            val encodedNodeId = TransactionCoder.NidEncoder.asString(nodeId)
            val encodedNode = encodedNodes(encodedNodeId)

            node.informeesOfNode shouldBe 'nonEmpty

            Right(node.informeesOfNode) shouldEqual
              TransactionCoder
                .protoNodeInfo(decodedVersionedTx.version, encodedNode)
                .map(_.informeesOfNode)
        }
      }
    }

    "do transactions with version override" in
      forAll(noDanglingRefGenTransaction, minSuccessful(50)) { tx =>
        forAll(transactionVersionGen, minSuccessful(5)) { txVer =>
          inside(
            TransactionCoder
              .encodeTransactionWithCustomVersion(
                TransactionCoder.NidEncoder,
                ValueCoder.CidEncoder,
                VersionedTransaction(txVer, tx),
              ),
          ) {
            case Left(EncodeError(msg)) =>
              // fuzzy sort of "failed because of the version override" test
              msg should include(txVer.toString)
            case Right(encodedTx) =>
              val decodedVersionedTx = assertRight(
                TransactionCoder
                  .decodeTransaction(
                    TransactionCoder.NidDecoder,
                    ValueCoder.CidDecoder,
                    encodedTx,
                  ),
              )
              decodedVersionedTx.transaction shouldBe minimalistTx(txVer, tx)
          }
        }
      }

    "succeed with encoding under later version if succeeded under earlier version" in
      forAll(noDanglingRefGenTransaction, minSuccessful(50)) { tx =>
        forAll(transactionVersionGen, transactionVersionGen, minSuccessful(20)) {
          (txVer1, txVer2) =>
            import VersionTimeline.Implicits._
            import scalaz.std.tuple._
            import scalaz.syntax.bifunctor._
            whenever(txVer1 != txVer2) {
              val orderedVers @ (txvMin, txvMax) =
                if (txVer2 precedes txVer1) (txVer2, txVer1) else (txVer1, txVer2)
              inside(
                orderedVers umap (
                    txVer =>
                      TransactionCoder
                        .encodeTransactionWithCustomVersion(
                          TransactionCoder.NidEncoder,
                          ValueCoder.CidEncoder,
                          VersionedTransaction(txVer, tx),
                        ),
                ),
              ) {
                case (Left(EncodeError(minMsg)), maxEnc) =>
                  // fuzzy sort of "failed because of the version override" test
                  minMsg should include(txvMin.toString)
                  maxEnc.left foreach (_.errorMessage should include(txvMax.toString))
                case (Right(encWithMin), Right(encWithMax)) =>
                  inside(
                    (encWithMin, encWithMax) umap (TransactionCoder
                      .decodeTransaction(
                        TransactionCoder.NidDecoder,
                        ValueCoder.CidDecoder,
                        _,
                      )),
                  ) {
                    case (Right(decWithMin), Right(decWithMax)) =>
                      decWithMin.transaction shouldBe minimalistTx(txvMin, tx)
                      decWithMin.transaction shouldBe
                        minimalistTx(txvMin, decWithMax.transaction)
                  }
              }
            }
        }
      }

    "transactions decoding should fail when unsupported value version received" in
      forAll(noDanglingRefGenTransaction, minSuccessful(50)) { tx =>
        whenever(isTransactionWithAtLeastOneVersionedValue(tx)) {
          forAll(unsupportedValueVersionGen, minSuccessful(20)) { badValVer =>
            ValueVersions.acceptedVersions.contains(badValVer) shouldEqual false

            val txWithBadValVersion = changeAllValueVersions(tx, badValVer)
            val encodedTxWithBadValVersion: proto.Transaction = assertRight(
              TransactionCoder
                .encodeTransactionWithCustomVersion(
                  TransactionCoder.NidEncoder,
                  ValueCoder.CidEncoder,
                  VersionedTransaction(defaultTransactionVersion, txWithBadValVersion),
                ),
            )

            TransactionCoder.decodeTransaction(
              TransactionCoder.NidDecoder,
              ValueCoder.CidDecoder,
              encodedTxWithBadValVersion,
            ) shouldEqual Left(DecodeError(s"Unsupported value version ${badValVer.protoValue}"))
          }
        }
      }

    "transactions decoding should fail when unsupported transaction version received" in
      forAll(noDanglingRefGenTransaction, minSuccessful(50)) { tx =>
        forAll(unsupportedTransactionVersionGen, minSuccessful(20)) {
          badTxVer: TransactionVersion =>
            TransactionVersions.acceptedVersions.contains(badTxVer) shouldEqual false

            val encodedTxWithBadTxVer: proto.Transaction = assertRight(
              TransactionCoder
                .encodeTransactionWithCustomVersion(
                  TransactionCoder.NidEncoder,
                  ValueCoder.CidEncoder,
                  VersionedTransaction(badTxVer, tx),
                ),
            )

            encodedTxWithBadTxVer.getVersion shouldEqual badTxVer.protoValue

            TransactionCoder.decodeTransaction(
              TransactionCoder.NidDecoder,
              ValueCoder.CidDecoder,
              encodedTxWithBadTxVer,
            ) shouldEqual Left(
              DecodeError(s"Unsupported transaction version ${badTxVer.protoValue}"),
            )
        }
      }

    "fetch decoding should fail when unsupported value version received" in forAll(
      fetchNodeGen,
      minSuccessful(5)) { node =>
      inside(
        TransactionCoder.encodeNode(
          TransactionCoder.NidEncoder,
          ValueCoder.CidEncoder,
          defaultTransactionVersion,
          NodeId(0),
          node)) {
        case Right(cn) if cn.hasFetch =>
          val badVersion = "I do not exist"
          TransactionCoder.decodeNode(
            TransactionCoder.NidDecoder,
            ValueCoder.CidDecoder,
            defaultTransactionVersion,
            cn.toBuilder.setFetch(cn.getFetch.toBuilder.setValueVersion(badVersion)).build
          ) should ===(Left(DecodeError(s"Unsupported template ID version $badVersion")))
      }
    }

    "do tx with a lot of root nodes" in {
      val node =
        Node.NodeCreate[Value.ContractId, Value.VersionedValue[Value.ContractId]](
          coid = absCid("#test-cid"),
          coinst = ContractInst(
            Identifier(
              PackageId.assertFromString("pkg-id"),
              QualifiedName.assertFromString("Test:Name"),
            ),
            VersionedValue(
              ValueVersions.acceptedVersions.last,
              ValueParty(Party.assertFromString("francesco")),
            ),
            "agreement",
          ),
          optLocation = None,
          signatories = Set(Party.assertFromString("alice")),
          stakeholders = Set(Party.assertFromString("alice"), Party.assertFromString("bob")),
          key = None,
        )
      val nodes = ImmArray((1 to 10000).map { nid =>
        NodeId(nid) -> node
      })
      val tx = TransactionVersions.assertAsVersionedTransaction(
        GenTransaction(nodes = HashMap(nodes.toSeq: _*), roots = nodes.map(_._1)))

      tx shouldEqual TransactionCoder
        .decodeTransaction(
          TransactionCoder.NidDecoder,
          ValueCoder.CidDecoder,
          TransactionCoder
            .encodeTransaction(
              TransactionCoder.NidEncoder,
              ValueCoder.CidEncoder,
              tx,
            )
            .right
            .get,
        )
        .right
        .get
    }
  }

  private def isTransactionWithAtLeastOneVersionedValue(
      tx: GenTransaction.WithTxValue[NodeId, Value.ContractId],
  ): Boolean =
    tx.nodes.values
      .exists {
        case _: Node.NodeCreate[_, _] | _: Node.NodeExercises[_, _, _] |
            _: Node.NodeLookupByKey[_, _] =>
          true
        case f: Node.NodeFetch[_, _] => f.key.isDefined
      }

  private def changeAllValueVersions(
      tx: GenTransaction.WithTxValue[NodeId, Value.ContractId],
      ver: ValueVersion,
  ): GenTransaction.WithTxValue[NodeId, Value.ContractId] =
    tx.map3(identity, identity, _.copy(version = ver))

  def withoutExerciseResult[Nid, Cid, Val](gn: GenNode[Nid, Cid, Val]): GenNode[Nid, Cid, Val] =
    gn match {
      case ne: NodeExercises[Nid, Cid, Val] => ne copy (exerciseResult = None)
      case _ => gn
    }
  def withoutContractKeyInExercise[Nid, Cid, Val](
      gn: GenNode[Nid, Cid, Val],
  ): GenNode[Nid, Cid, Val] =
    gn match {
      case ne: NodeExercises[Nid, Cid, Val] => ne copy (key = None)
      case _ => gn
    }
  def withoutMaintainersInExercise[Nid, Cid, Val](
      gn: GenNode[Nid, Cid, Val],
  ): GenNode[Nid, Cid, Val] =
    gn match {
      case ne: NodeExercises[Nid, Cid, Val] =>
        ne copy (key = ne.key.map(_.copy(maintainers = Set.empty)))
      case _ => gn
    }

  // FIXME: https://github.com/digital-asset/daml/issues/7622
  // Fix the usage of this function in the test, once `byKey` is added to the serialization format.
  def withoutByKeyFlag[Nid, Cid, Val](gn: GenNode[Nid, Cid, Val]): GenNode[Nid, Cid, Val] =
    gn match {
      case ne: NodeExercises[Nid, Cid, Val] =>
        ne.copy(byKey = false)
      case fe: NodeFetch[Cid, Val] =>
        fe.copy(byKey = false)
      case _ => gn
    }

  def transactionWithout[Nid, Cid, Val](
      t: GenTransaction[Nid, Cid, Val],
      f: GenNode[Nid, Cid, Val] => GenNode[Nid, Cid, Val],
  ): GenTransaction[Nid, Cid, Val] =
    t copy (nodes = t.nodes.transform((_, gn) => f(gn)))

  def minimalistTx[Nid, Cid, Val](
      txvMin: TransactionVersion,
      tx: GenTransaction[Nid, Cid, Val],
  ): GenTransaction[Nid, Cid, Val] = {
    def condApply(
        before: TransactionVersion,
        f: GenNode[Nid, Cid, Val] => GenNode[Nid, Cid, Val],
    ): GenNode[Nid, Cid, Val] => GenNode[Nid, Cid, Val] =
      if (txvMin precedes before) f else identity

    transactionWithout(
      tx,
      condApply(minMaintainersInExercise, withoutMaintainersInExercise)
        .compose(condApply(minContractKeyInExercise, withoutContractKeyInExercise))
        .compose(condApply(minExerciseResult, withoutExerciseResult))
        .compose(withoutByKeyFlag[Nid, Cid, Val]),
    )
  }

  private def absCid(s: String): Value.ContractId =
    Value.ContractId.assertFromString(s)

}
