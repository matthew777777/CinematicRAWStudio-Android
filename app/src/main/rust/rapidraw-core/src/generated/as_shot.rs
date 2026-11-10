// Generated from pinned RapidRAW; AGPL-3.0-or-later. Do not edit.
use crate::baseline_exposure::RawExposure;
use crate::white_balance::WhiteBalance;
#[derive(Clone, Copy, Debug)]
pub struct AsShot {
    pub white_balance: WhiteBalance,
    pub exposure: RawExposure,
}

impl AsShot {
    pub fn reference() -> Self {
        Self {
            white_balance: WhiteBalance::reference(),
            exposure: RawExposure::default(),
        }
    }
}

