package branch_pos

import (
	per_document "go-viki/internal/helpers/models/permissions"
)

type BranchBindingModel struct {
	per_document.NoPer `bson:",inline"`

	DomainId string `bson:"domain_id,omitempty" json:"domain_id,omitempty" form:"domain_id,omitempty"`

	Org string `bson:"org,omitempty" json:"org,omitempty" form:"org,omitempty"`

	Company string `bson:"company,omitempty" json:"company,omitempty" form:"company,omitempty"`

	Branch string `bson:"branch,omitempty" json:"branch,omitempty" form:"branch,omitempty"`

	CompanyProfile string `bson:"company_profile,omitempty" json:"company_profile,omitempty" form:"company_profile,omitempty"`

	SchemaVersion string `bson:"schema_version,omitempty" json:"schema_version,omitempty" form:"schema_version,omitempty"`

	Revision string `bson:"revision,omitempty" json:"revision,omitempty" form:"revision,omitempty"`

	SelectedMasterDeviceId string `bson:"selected_master_device_id,omitempty" json:"selected_master_device_id,omitempty" form:"selected_master_device_id,omitempty"`

	AuthorityEpoch string `bson:"authority_epoch,omitempty" json:"authority_epoch,omitempty" form:"authority_epoch,omitempty"`
}

func (p *BranchBindingModel) CollectionName() string {
	return "branch_pos__branch_binding"
}
