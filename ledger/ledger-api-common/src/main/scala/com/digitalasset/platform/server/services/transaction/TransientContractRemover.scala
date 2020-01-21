// Copyright (c) 2020 The DAML Authors. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.platform.server.services.transaction

import com.digitalasset.ledger.api.domain.ContractId
import com.digitalasset.ledger.api.domain.Event.{ArchivedEvent, CreateOrArchiveEvent, CreatedEvent}

import scala.collection.{breakOut, mutable}

object TransientContractRemover {

  /**
    * Cancels out witnesses on creates and archives that are about the same contract.
    * If no witnesses remain on either, the node is removed.
    *
    * @param nodes Must be sorted by event index.
    * @throws IllegalArgumentException if the argument is not sorted properly.
    */
  def removeTransients(nodes: List[CreateOrArchiveEvent]): List[CreateOrArchiveEvent] = {

    val resultBuilder = new Array[Option[CreateOrArchiveEvent]](nodes.size)
    val creationByContractId = new mutable.HashMap[ContractId, (Int, CreatedEvent)]()

    nodes.zipWithIndex.foreach {
      case (event, indexInList) =>
        // Each call adds a new (possibly null) element to resultBuilder, and may update items previously added
        updateResultBuilder(resultBuilder, creationByContractId, event, indexInList)
    }

    resultBuilder.collect { case Some(v) if v.witnessParties.nonEmpty => v }(breakOut)
  }

  /**
    * Update resultBuilder given the next event.
    * This will insert a new element and possibly update a previous one.
    */
  private def updateResultBuilder(
      resultBuilder: Array[Option[CreateOrArchiveEvent]],
      creationByContractId: mutable.HashMap[ContractId, (Int, CreatedEvent)],
      event: CreateOrArchiveEvent,
      indexInList: Int
  ): Unit =
    event match {
      case createdEvent @ CreatedEvent(_, contractId, _, _, witnessParties, _, _, _, _) =>
        if (witnessParties.nonEmpty) {
          resultBuilder.update(indexInList, Some(event))
          val _ = creationByContractId.put(contractId, indexInList -> createdEvent)
        }
      case archivedEvent @ ArchivedEvent(_, contractId, _, witnessParties) =>
        if (witnessParties.nonEmpty) {
          creationByContractId
            .get(contractId)
            .fold[Unit] {
              // No matching create for this archive. Insert as is.
              resultBuilder.update(indexInList, Some(event))
            } {
              case (createdEventIndex, createdEvent) =>
                // Defensive code to ensure that the set of parties the events are disclosed to are not different.
                if (witnessParties.toSet != createdEvent.witnessParties)
                  throw new IllegalArgumentException(
                    s"Created and Archived event stakeholders are different in $createdEvent, $archivedEvent")

                resultBuilder.update(createdEventIndex, None)
                resultBuilder.update(indexInList, None)
            }
        }
    }

}
