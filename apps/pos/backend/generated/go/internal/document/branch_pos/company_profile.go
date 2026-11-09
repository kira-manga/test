package branch_pos

import (
	per_document "go-viki/internal/helpers/models/permissions"
)

type CompanyProfileModel struct {
	per_document.NoPer `bson:",inline"`

	DomainId string `bson:"domain_id,omitempty" json:"domain_id,omitempty" form:"domain_id,omitempty"`

	Org string `bson:"org,omitempty" json:"org,omitempty" form:"org,omitempty"`

	Company string `bson:"company,omitempty" json:"company,omitempty" form:"company,omitempty"`

	SchemaVersion string `bson:"schema_version,omitempty" json:"schema_version,omitempty" form:"schema_version,omitempty"`

	Revision string `bson:"revision,omitempty" json:"revision,omitempty" form:"revision,omitempty"`

	Activity string `bson:"activity,omitempty" json:"activity,omitempty" form:"activity,omitempty"`

	Currency string `bson:"currency,omitempty" json:"currency,omitempty" form:"currency,omitempty"`

	Timezone string `bson:"timezone,omitempty" json:"timezone,omitempty" form:"timezone,omitempty"`

	ActiveReleaseHash string `bson:"active_release_hash,omitempty" json:"active_release_hash,omitempty" form:"active_release_hash,omitempty"`
}

func (p *CompanyProfileModel) CollectionName() string {
	return "branch_pos__company_profile"
}
