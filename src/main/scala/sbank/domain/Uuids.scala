package sbank.domain

import java.util.UUID

import com.fasterxml.uuid.impl.{RandomBasedGenerator, TimeBasedEpochGenerator}
import com.fasterxml.uuid.Generators

object Uuids {

  private val epoch: TimeBasedEpochGenerator = Generators.timeBasedEpochGenerator()
  private val rand: RandomBasedGenerator     = Generators.randomBasedGenerator()

  /**
    * UUIDv7: time-ordered, so new primary keys append to the end of the B-tree index.
    */
  def timeOrdered(): UUID = epoch.generate()

  /**
    * UUIDv4. Use this when only a prefix of the string form is kept: a v7's leading characters are
    * its timestamp, so a truncated v7 collides for values generated close together.
    */
  def random(): UUID = rand.generate()

}
