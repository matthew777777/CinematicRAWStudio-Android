use serde_json::Value;
use crate::image_processing::is_effect_enabled;
pub fn is_enabled(adjustments: &Value) -> bool {
    is_effect_enabled(
        adjustments,
        "negativeConversion",
        "negativeConversionEnabled",
    )
}

