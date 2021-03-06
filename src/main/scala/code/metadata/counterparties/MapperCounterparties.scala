package code.metadata.counterparties

import java.util.{Date, UUID}

import code.model._
import code.model.dataAccess.APIUser
import code.util.{DefaultStringField, MappedAccountNumber, MappedUUID}
import net.liftweb.common.{Box, Full, Loggable}
import net.liftweb.mapper.{By, _}
import net.liftweb.util.Helpers.tryo

object MapperCounterparties extends Counterparties with Loggable {
  override def getOrCreateMetadata(originalPartyBankId: BankId, originalPartyAccountId: AccountId, otherParty: Counterparty): CounterpartyMetadata = {

    /**
     * Generates a new alias name that is guaranteed not to collide with any existing public alias names
     * for the account in question
     */
    def newPublicAliasName(): String = {
      val firstAliasAttempt = "ALIAS_" + UUID.randomUUID.toString.toUpperCase.take(6)

      /**
       * Returns true if @publicAlias is already the name of a public alias within @account
       */
      def isDuplicate(publicAlias: String) : Boolean = {
        MappedCounterpartyMetadata.find(
          By(MappedCounterpartyMetadata.thisAccountBankId, originalPartyBankId.value),
          By(MappedCounterpartyMetadata.thisAccountId, originalPartyAccountId.value),
          By(MappedCounterpartyMetadata.publicAlias, publicAlias)
        ).isDefined
      }

      /**
       * Appends things to @publicAlias until it a unique public alias name within @account
       */
      def appendUntilUnique(publicAlias: String): String = {
        val newAlias = publicAlias + UUID.randomUUID.toString.toUpperCase.take(1)
        // Recursive call.
        if (isDuplicate(newAlias)) appendUntilUnique(newAlias)
        else newAlias
      }

      if (isDuplicate(firstAliasAttempt)) appendUntilUnique(firstAliasAttempt)
      else firstAliasAttempt
    }


    //can't find by MappedCounterpartyMetadata.counterpartyId = otherParty.id because in this implementation
    //if the metadata doesn't exist, the id field of the OtherBankAccount is not known yet, and will be empty
    def findMappedCounterpartyMetadata(originalPartyBankId: BankId, originalPartyAccountId: AccountId,
                                       otherParty: Counterparty) : Box[MappedCounterpartyMetadata] = {
      MappedCounterpartyMetadata.find(
        By(MappedCounterpartyMetadata.thisAccountBankId, originalPartyBankId.value),
        By(MappedCounterpartyMetadata.thisAccountId, originalPartyAccountId.value),
        By(MappedCounterpartyMetadata.holder, otherParty.label),
        By(MappedCounterpartyMetadata.accountNumber, otherParty.thisAccountId.value))
    }

    val existing = findMappedCounterpartyMetadata(originalPartyBankId, originalPartyAccountId, otherParty)

    existing match {
      case Full(e) => e
      // Create it!
      case _ => {
        logger.debug("Before creating MappedCounterpartyMetadata")
        // Store a record that contains counterparty information from the perspective of an account at a bank
        MappedCounterpartyMetadata.create
          // Core info
          .thisAccountBankId(originalPartyBankId.value)
          .thisAccountId(originalPartyAccountId.value)
          .holder(otherParty.label) // The main human readable identifier for this counter party from the perspective of the account holder
          .publicAlias(newPublicAliasName()) // The public alias this account gives to the counterparty.
          .accountNumber(otherParty.thisAccountId.value)
          // otherParty.metadata is None at this point
          //.imageUrl("www.example.com/image.jpg")
          //.moreInfo("This is hardcoded moreInfo")
          .saveMe
      }
    }
  }

  // Get all counterparty metadata for a single OBP account
  override def getMetadatas(originalPartyBankId: BankId, originalPartyAccountId: AccountId): List[CounterpartyMetadata] = {
    MappedCounterpartyMetadata.findAll(
      By(MappedCounterpartyMetadata.thisAccountBankId, originalPartyBankId.value),
      By(MappedCounterpartyMetadata.thisAccountId, originalPartyAccountId.value)
    )
  }

  override def getMetadata(originalPartyBankId: BankId, originalPartyAccountId: AccountId, counterpartyMetadataId: String): Box[CounterpartyMetadata] = {
    /**
     * This particular implementation requires the metadata id to be the same as the otherParty (OtherBankAccount) id
     */
    MappedCounterpartyMetadata.find(
      By(MappedCounterpartyMetadata.thisAccountBankId, originalPartyBankId.value),
      By(MappedCounterpartyMetadata.thisAccountId, originalPartyAccountId.value),
      By(MappedCounterpartyMetadata.counterpartyId, counterpartyMetadataId)
    )
  }

  def addMetadata(bankId: BankId, accountId : AccountId): Box[CounterpartyMetadata] = {
    Full(
    MappedCounterpartyMetadata.create
      .thisAccountBankId(bankId.value)
      .thisAccountId(accountId.value)
      .saveMe
    )
  }


  override def getCounterparty(counterPartyId : String): Box[CounterpartyTrait] = {
    MappedCounterparty.find(By(MappedCounterparty.mCounterPartyId, counterPartyId))
  }
  override def getCounterpartyByIban(iban : String): Box[CounterpartyTrait] = {
      MappedCounterparty.find(By(MappedCounterparty.mOtherAccountRoutingAddress, iban))
    }

  override def createCounterparty(createdByUserId: String,
                                  thisBankId: String,
                                  thisAccountId : String,
                                  thisViewId : String,
                                  name: String,
                                  otherBankId : String,
                                  otherAccountId:String,
                                  otherAccountRoutingScheme : String,
                                  otherAccountRoutingAddress : String,
                                  otherBankRoutingScheme : String,
                                  otherBankRoutingAddress : String,
                                  isBeneficiary: Boolean
                                 ): Box[CounterpartyTrait] = {
    val metadata = MappedCounterpartyMetadata.create
                                              .thisAccountBankId(thisBankId)
                                              .thisAccountId(thisAccountId)
                                              .holder(name)
                                              .saveMe

    Some(
    MappedCounterparty.create
      .mCounterPartyId(metadata.metadataId)
      .mName(name)
      .mCreatedByUserId(createdByUserId)
      .mThisBankId(thisBankId)
      .mThisAccountId(thisAccountId)
      .mThisViewId(thisViewId)
      .mOtherBankId(otherBankId)
      .mOtherAccountId(otherAccountId)
      .mOtherAccountRoutingScheme(otherAccountRoutingScheme)
      .mOtherAccountRoutingAddress(otherAccountRoutingAddress)
      .mOtherBankRoutingScheme(otherBankRoutingScheme)
      .mOtherBankRoutingAddress(otherBankRoutingAddress)
      .mIsBeneficiary(isBeneficiary)
      .saveMe()
    )
  }

 override def checkCounterpartyAvailable(
                               name: String,
                               thisBankId: String,
                               thisAccountId: String,
                               thisViewId: String
                             ): Boolean = {
   val counterparties = MappedCounterparty.findAll(
     By(MappedCounterparty.mName, name),
     By(MappedCounterparty.mThisBankId, thisBankId),
     By(MappedCounterparty.mThisAccountId, thisAccountId),
     By(MappedCounterparty.mThisViewId, thisViewId)
   )

   val available: Boolean = counterparties.size match {
     case 0 => true
     case _ => false
   }

   available
  }
}

class MappedCounterpartyMetadata extends CounterpartyMetadata with LongKeyedMapper[MappedCounterpartyMetadata] with IdPK with CreatedUpdated {
  override def getSingleton = MappedCounterpartyMetadata

  object counterpartyId extends MappedUUID(this)

  //these define the obp account to which this counterparty belongs
  object thisAccountBankId extends MappedString(this, 255)
  object thisAccountId extends MappedString(this, 255)

  //these define the counterparty
  object holder extends MappedString(this, 255)
  object accountNumber extends MappedAccountNumber(this)

  //this is the counterparty's metadata
  object publicAlias extends DefaultStringField(this)
  object privateAlias extends DefaultStringField(this)
  object moreInfo extends DefaultStringField(this)
  object url extends DefaultStringField(this)
  object imageUrl extends DefaultStringField(this)
  object openCorporatesUrl extends DefaultStringField(this)

  object physicalLocation extends MappedLongForeignKey(this, MappedCounterpartyWhereTag)
  object corporateLocation extends MappedLongForeignKey(this, MappedCounterpartyWhereTag)

  /**
   * Evaluates f, and then attempts to save. If no exceptions are thrown and save executes successfully,
   * true is returned. If an exception is thrown or if the save fails, false is returned.
   * @param f the expression to evaluate (e.g. imageUrl("http://example.com/foo.png")
   * @return If saving the model worked after having evaluated f
   */
  private def trySave(f : => Any) : Boolean =
    tryo{
      f
      save()
    }.getOrElse(false)

  private def setWhere(whereTag : Box[MappedCounterpartyWhereTag])
                      (userId: UserId, datePosted : Date, longitude : Double, latitude : Double) : Box[MappedCounterpartyWhereTag] = {
    val toUpdate = whereTag match {
      case Full(c) => c
      case _ => MappedCounterpartyWhereTag.create
    }

    tryo{
      toUpdate
        .user(userId.value)
        .date(datePosted)
        .geoLongitude(longitude)
        .geoLatitude(latitude)
        .saveMe
    }
  }

  def setCorporateLocation(userId: UserId, datePosted : Date, longitude : Double, latitude : Double) : Boolean = {
    //save where tag
    val savedWhere = setWhere(corporateLocation.obj)(userId, datePosted, longitude, latitude)
    //set where tag for counterparty
    savedWhere.map(location => trySave{corporateLocation(location)}).getOrElse(false)
  }

  def setPhysicalLocation(userId: UserId, datePosted : Date, longitude : Double, latitude : Double) : Boolean = {
    //save where tag
    val savedWhere = setWhere(physicalLocation.obj)(userId, datePosted, longitude, latitude)
    //set where tag for counterparty
    savedWhere.map(location => trySave{physicalLocation(location)}).getOrElse(false)
  }

  override def metadataId: String = counterpartyId.get
  override def getAccountNumber: String = accountNumber.get
  override def getHolder: String = holder.get
  override def getPublicAlias: String = publicAlias.get
  override def getCorporateLocation: Option[GeoTag] =
    corporateLocation.obj
  override def getOpenCorporatesURL: String = openCorporatesUrl.get
  override def getMoreInfo: String = moreInfo.get
  override def getPrivateAlias: String = privateAlias.get
  override def getImageURL: String = imageUrl.get
  override def getPhysicalLocation: Option[GeoTag] =
    physicalLocation.obj
  override def getUrl: String = url.get

  override val addPhysicalLocation: (UserId, Date, Double, Double) => Boolean = setPhysicalLocation _
  override val addCorporateLocation: (UserId, Date, Double, Double) => Boolean = setCorporateLocation _
  override val addPrivateAlias: (String) => Boolean = (x) =>
    trySave{privateAlias(x)}
  override val addURL: (String) => Boolean = (x) =>
    trySave{url(x)}
  override val addMoreInfo: (String) => Boolean = (x) =>
    trySave{moreInfo(x)}
  override val addPublicAlias: (String) => Boolean = (x) =>
    trySave{publicAlias(x)}
  override val addOpenCorporatesURL: (String) => Boolean = (x) =>
    trySave{openCorporatesUrl(x)}
  override val addImageURL: (String) => Boolean = (x) =>
    trySave{imageUrl(x)}
  override val deleteCorporateLocation = () =>
    corporateLocation.obj.map(_.delete_!).getOrElse(false)
  override val deletePhysicalLocation = () =>
    physicalLocation.obj.map(_.delete_!).getOrElse(false)

}

object MappedCounterpartyMetadata extends MappedCounterpartyMetadata with LongKeyedMetaMapper[MappedCounterpartyMetadata] {
  override def dbIndexes =
    UniqueIndex(counterpartyId) ::
    Index(thisAccountBankId, thisAccountId, holder, accountNumber) ::
    super.dbIndexes
}

class MappedCounterpartyWhereTag extends GeoTag with LongKeyedMapper[MappedCounterpartyWhereTag] with IdPK with CreatedUpdated {

  def getSingleton = MappedCounterpartyWhereTag

  object user extends MappedLongForeignKey(this, APIUser)
  object date extends MappedDateTime(this)

  //TODO: require these to be valid latitude/longitudes
  object geoLatitude extends MappedDouble(this)
  object geoLongitude extends MappedDouble(this)

  override def postedBy: Box[User] = user.obj
  override def datePosted: Date = date.get
  override def latitude: Double = geoLatitude.get
  override def longitude: Double = geoLongitude.get
}

object MappedCounterpartyWhereTag extends MappedCounterpartyWhereTag with LongKeyedMetaMapper[MappedCounterpartyWhereTag]


class MappedCounterparty extends CounterpartyTrait with LongKeyedMapper[MappedCounterparty] with IdPK with CreatedUpdated {
  def getSingleton = MappedCounterparty

  object mCreatedByUserId extends MappedString(this, 36)
  object mName extends MappedString(this, 36)
  object mThisBankId extends MappedString(this, 36)
  object mThisAccountId extends MappedString(this, 255)
  object mThisViewId extends MappedString(this, 36)
  object mOtherBankId extends MappedString(this, 36)
  object mOtherAccountId extends MappedString(this, 36)
  object mOtherAccountProvider extends MappedString(this, 36)
  object mCounterPartyId extends MappedString(this, 36)
  object mOtherAccountRoutingScheme extends MappedString(this, 255)
  object mOtherAccountRoutingAddress extends MappedString(this, 255)
  object mOtherBankRoutingScheme extends MappedString(this, 255)
  object mOtherBankRoutingAddress extends MappedString(this, 255)
  object mIsBeneficiary extends MappedBoolean(this)



  override def createdByUserId = mCreatedByUserId.get
  override def name = mName.get
  override def thisBankId = mThisBankId.get
  override def thisAccountId = mThisAccountId.get
  override def thisViewId = mThisViewId.get
  override def otherBankId = mOtherBankId.get
  override def otherAccountId: String = mOtherAccountId.get
  override def otherAccountProvider: String = mOtherAccountProvider.get
  override def counterPartyId = mCounterPartyId.get
  override def otherAccountRoutingScheme = mOtherAccountRoutingScheme.get
  override def otherAccountRoutingAddress = mOtherAccountRoutingAddress.get
  override def otherBankRoutingScheme: String = mOtherBankRoutingScheme.get
  override def otherBankRoutingAddress: String = mOtherBankRoutingAddress.get
  override def isBeneficiary: Boolean = mIsBeneficiary.get
}

object MappedCounterparty extends MappedCounterparty with LongKeyedMetaMapper[MappedCounterparty] {
  override def dbIndexes = UniqueIndex(mCounterPartyId) :: UniqueIndex(mName, mThisBankId, mThisAccountId, mThisViewId) :: super.dbIndexes
}
