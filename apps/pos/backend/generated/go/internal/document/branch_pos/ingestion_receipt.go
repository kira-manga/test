package branch_pos

import (
	per_document "go-viki/internal/helpers/models/permissions"
	"time"
)

type IngestionReceiptModel struct {
	per_document.NoPer `bson:",inline"`

	DomainId string `bson:"domain_id,omitempty" json:"domain_id,omitempty" form:"domain_id,omitempty"`

	Org string `bson:"org,omitempty" json:"org,omitempty" form:"org,omitempty"`

	Company string `bson:"company,omitempty" json:"company,omitempty" form:"company,omitempty"`

	Branch string `bson:"branch,omitempty" json:"branch,omitempty" form:"branch,omitempty"`

	SchemaVersion string `bson:"schema_version,omitempty" json:"schema_version,omitempty" form:"schema_version,omitempty"`

	OperationId string `bson:"operation_id,omitempty" json:"operation_id,omitempty" form:"operation_id,omitempty"`

	AuthorityEpoch string `bson:"authority_epoch,omitempty" json:"authority_epoch,omitempty" form:"authority_epoch,omitempty"`

	JournalSequence string `bson:"journal_sequence,omitempty" json:"journal_sequence,omitempty" form:"journal_sequence,omitempty"`

	ContentHash string `bson:"content_hash,omitempty" json:"content_hash,omitempty" form:"content_hash,omitempty"`

	Disposition string `bson:"disposition,omitempty" json:"disposition,omitempty" form:"disposition,omitempty"`

	StoredAt *time.Time `bson:"stored_at,omitempty" json:"stored_at,omitempty" form:"stored_at,omitempty"`
}

func (p *IngestionReceiptModel) CollectionName() string {
	return "branch_pos__ingestion_receipt"
}
