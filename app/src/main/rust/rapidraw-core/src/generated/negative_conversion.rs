// Generated from pinned RapidRAW; AGPL-3.0-or-later. Do not edit.
use serde_json::Value;
pub fn is_enabled(adjustments: &Value) -> bool {
    crate::image_processing::is_effect_enabled(
        adjustments,
        "negativeConversion",
        "negativeConversionEnabled",
    )
}

