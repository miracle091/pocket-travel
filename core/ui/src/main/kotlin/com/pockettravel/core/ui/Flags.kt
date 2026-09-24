package com.pockettravel.core.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Bandiere nazionali come VectorDrawable (res/drawable/ic_flag_<iso2>.xml), da
// westnordost/flags-vector-drawables-android (fonte: Wikipedia, licenza di ciascuna bandiera).
// Mappa esplicita e non getIdentifier: cosi' il resource shrinker vede i riferimenti.

@DrawableRes
fun flagRes(countryCode: String): Int? = when (countryCode.lowercase()) {
    "ad" -> R.drawable.ic_flag_ad
    "ae" -> R.drawable.ic_flag_ae
    "af" -> R.drawable.ic_flag_af
    "ag" -> R.drawable.ic_flag_ag
    "ai" -> R.drawable.ic_flag_ai
    "al" -> R.drawable.ic_flag_al
    "am" -> R.drawable.ic_flag_am
    "an" -> R.drawable.ic_flag_an
    "ao" -> R.drawable.ic_flag_ao
    "aq" -> R.drawable.ic_flag_aq
    "ar" -> R.drawable.ic_flag_ar
    "as" -> R.drawable.ic_flag_as
    "at" -> R.drawable.ic_flag_at
    "au" -> R.drawable.ic_flag_au
    "aw" -> R.drawable.ic_flag_aw
    "ax" -> R.drawable.ic_flag_ax
    "az" -> R.drawable.ic_flag_az
    "ba" -> R.drawable.ic_flag_ba
    "bb" -> R.drawable.ic_flag_bb
    "bd" -> R.drawable.ic_flag_bd
    "be" -> R.drawable.ic_flag_be
    "bf" -> R.drawable.ic_flag_bf
    "bg" -> R.drawable.ic_flag_bg
    "bh" -> R.drawable.ic_flag_bh
    "bi" -> R.drawable.ic_flag_bi
    "bj" -> R.drawable.ic_flag_bj
    "bl" -> R.drawable.ic_flag_bl
    "bm" -> R.drawable.ic_flag_bm
    "bn" -> R.drawable.ic_flag_bn
    "bo" -> R.drawable.ic_flag_bo
    "bq" -> R.drawable.ic_flag_bq
    "br" -> R.drawable.ic_flag_br
    "bs" -> R.drawable.ic_flag_bs
    "bt" -> R.drawable.ic_flag_bt
    "bv" -> R.drawable.ic_flag_bv
    "bw" -> R.drawable.ic_flag_bw
    "by" -> R.drawable.ic_flag_by
    "bz" -> R.drawable.ic_flag_bz
    "ca" -> R.drawable.ic_flag_ca
    "cc" -> R.drawable.ic_flag_cc
    "cd" -> R.drawable.ic_flag_cd
    "cf" -> R.drawable.ic_flag_cf
    "cg" -> R.drawable.ic_flag_cg
    "ch" -> R.drawable.ic_flag_ch
    "ci" -> R.drawable.ic_flag_ci
    "ck" -> R.drawable.ic_flag_ck
    "cl" -> R.drawable.ic_flag_cl
    "cm" -> R.drawable.ic_flag_cm
    "cn" -> R.drawable.ic_flag_cn
    "co" -> R.drawable.ic_flag_co
    "cr" -> R.drawable.ic_flag_cr
    "cu" -> R.drawable.ic_flag_cu
    "cv" -> R.drawable.ic_flag_cv
    "cw" -> R.drawable.ic_flag_cw
    "cx" -> R.drawable.ic_flag_cx
    "cy" -> R.drawable.ic_flag_cy
    "cz" -> R.drawable.ic_flag_cz
    "de" -> R.drawable.ic_flag_de
    "dj" -> R.drawable.ic_flag_dj
    "dk" -> R.drawable.ic_flag_dk
    "dm" -> R.drawable.ic_flag_dm
    "do" -> R.drawable.ic_flag_do
    "dz" -> R.drawable.ic_flag_dz
    "ec" -> R.drawable.ic_flag_ec
    "ee" -> R.drawable.ic_flag_ee
    "eg" -> R.drawable.ic_flag_eg
    "eh" -> R.drawable.ic_flag_eh
    "er" -> R.drawable.ic_flag_er
    "es" -> R.drawable.ic_flag_es
    "et" -> R.drawable.ic_flag_et
    "eu" -> R.drawable.ic_flag_eu
    "fi" -> R.drawable.ic_flag_fi
    "fj" -> R.drawable.ic_flag_fj
    "fk" -> R.drawable.ic_flag_fk
    "fm" -> R.drawable.ic_flag_fm
    "fo" -> R.drawable.ic_flag_fo
    "fr" -> R.drawable.ic_flag_fr
    "ga" -> R.drawable.ic_flag_ga
    "gb" -> R.drawable.ic_flag_gb
    "gd" -> R.drawable.ic_flag_gd
    "ge" -> R.drawable.ic_flag_ge
    "gf" -> R.drawable.ic_flag_gf
    "gg" -> R.drawable.ic_flag_gg
    "gh" -> R.drawable.ic_flag_gh
    "gi" -> R.drawable.ic_flag_gi
    "gl" -> R.drawable.ic_flag_gl
    "gm" -> R.drawable.ic_flag_gm
    "gn" -> R.drawable.ic_flag_gn
    "gp" -> R.drawable.ic_flag_gp
    "gq" -> R.drawable.ic_flag_gq
    "gr" -> R.drawable.ic_flag_gr
    "gs" -> R.drawable.ic_flag_gs
    "gt" -> R.drawable.ic_flag_gt
    "gu" -> R.drawable.ic_flag_gu
    "gw" -> R.drawable.ic_flag_gw
    "gy" -> R.drawable.ic_flag_gy
    "hk" -> R.drawable.ic_flag_hk
    "hm" -> R.drawable.ic_flag_hm
    "hn" -> R.drawable.ic_flag_hn
    "hr" -> R.drawable.ic_flag_hr
    "ht" -> R.drawable.ic_flag_ht
    "hu" -> R.drawable.ic_flag_hu
    "ic" -> R.drawable.ic_flag_ic
    "id" -> R.drawable.ic_flag_id
    "ie" -> R.drawable.ic_flag_ie
    "il" -> R.drawable.ic_flag_il
    "im" -> R.drawable.ic_flag_im
    "in" -> R.drawable.ic_flag_in
    "io" -> R.drawable.ic_flag_io
    "iq" -> R.drawable.ic_flag_iq
    "ir" -> R.drawable.ic_flag_ir
    "is" -> R.drawable.ic_flag_is
    "it" -> R.drawable.ic_flag_it
    "je" -> R.drawable.ic_flag_je
    "jm" -> R.drawable.ic_flag_jm
    "jo" -> R.drawable.ic_flag_jo
    "jp" -> R.drawable.ic_flag_jp
    "ke" -> R.drawable.ic_flag_ke
    "kg" -> R.drawable.ic_flag_kg
    "kh" -> R.drawable.ic_flag_kh
    "ki" -> R.drawable.ic_flag_ki
    "km" -> R.drawable.ic_flag_km
    "kn" -> R.drawable.ic_flag_kn
    "kp" -> R.drawable.ic_flag_kp
    "kr" -> R.drawable.ic_flag_kr
    "kw" -> R.drawable.ic_flag_kw
    "ky" -> R.drawable.ic_flag_ky
    "kz" -> R.drawable.ic_flag_kz
    "la" -> R.drawable.ic_flag_la
    "lb" -> R.drawable.ic_flag_lb
    "lc" -> R.drawable.ic_flag_lc
    "li" -> R.drawable.ic_flag_li
    "lk" -> R.drawable.ic_flag_lk
    "lr" -> R.drawable.ic_flag_lr
    "ls" -> R.drawable.ic_flag_ls
    "lt" -> R.drawable.ic_flag_lt
    "lu" -> R.drawable.ic_flag_lu
    "lv" -> R.drawable.ic_flag_lv
    "ly" -> R.drawable.ic_flag_ly
    "ma" -> R.drawable.ic_flag_ma
    "mc" -> R.drawable.ic_flag_mc
    "md" -> R.drawable.ic_flag_md
    "me" -> R.drawable.ic_flag_me
    "mf" -> R.drawable.ic_flag_mf
    "mg" -> R.drawable.ic_flag_mg
    "mh" -> R.drawable.ic_flag_mh
    "mk" -> R.drawable.ic_flag_mk
    "ml" -> R.drawable.ic_flag_ml
    "mm" -> R.drawable.ic_flag_mm
    "mn" -> R.drawable.ic_flag_mn
    "mo" -> R.drawable.ic_flag_mo
    "mp" -> R.drawable.ic_flag_mp
    "mq" -> R.drawable.ic_flag_mq
    "mr" -> R.drawable.ic_flag_mr
    "ms" -> R.drawable.ic_flag_ms
    "mt" -> R.drawable.ic_flag_mt
    "mu" -> R.drawable.ic_flag_mu
    "mv" -> R.drawable.ic_flag_mv
    "mw" -> R.drawable.ic_flag_mw
    "mx" -> R.drawable.ic_flag_mx
    "my" -> R.drawable.ic_flag_my
    "mz" -> R.drawable.ic_flag_mz
    "na" -> R.drawable.ic_flag_na
    "nc" -> R.drawable.ic_flag_nc
    "ne" -> R.drawable.ic_flag_ne
    "nf" -> R.drawable.ic_flag_nf
    "ng" -> R.drawable.ic_flag_ng
    "ni" -> R.drawable.ic_flag_ni
    "nl" -> R.drawable.ic_flag_nl
    "no" -> R.drawable.ic_flag_no
    "np" -> R.drawable.ic_flag_np
    "nr" -> R.drawable.ic_flag_nr
    "nu" -> R.drawable.ic_flag_nu
    "nz" -> R.drawable.ic_flag_nz
    "om" -> R.drawable.ic_flag_om
    "pa" -> R.drawable.ic_flag_pa
    "pe" -> R.drawable.ic_flag_pe
    "pf" -> R.drawable.ic_flag_pf
    "pg" -> R.drawable.ic_flag_pg
    "ph" -> R.drawable.ic_flag_ph
    "pk" -> R.drawable.ic_flag_pk
    "pl" -> R.drawable.ic_flag_pl
    "pm" -> R.drawable.ic_flag_pm
    "pn" -> R.drawable.ic_flag_pn
    "pr" -> R.drawable.ic_flag_pr
    "ps" -> R.drawable.ic_flag_ps
    "pt" -> R.drawable.ic_flag_pt
    "pw" -> R.drawable.ic_flag_pw
    "py" -> R.drawable.ic_flag_py
    "qa" -> R.drawable.ic_flag_qa
    "re" -> R.drawable.ic_flag_re
    "ro" -> R.drawable.ic_flag_ro
    "rs" -> R.drawable.ic_flag_rs
    "ru" -> R.drawable.ic_flag_ru
    "rw" -> R.drawable.ic_flag_rw
    "sa" -> R.drawable.ic_flag_sa
    "sb" -> R.drawable.ic_flag_sb
    "sc" -> R.drawable.ic_flag_sc
    "sd" -> R.drawable.ic_flag_sd
    "se" -> R.drawable.ic_flag_se
    "sg" -> R.drawable.ic_flag_sg
    "sh" -> R.drawable.ic_flag_sh
    "si" -> R.drawable.ic_flag_si
    "sj" -> R.drawable.ic_flag_sj
    "sk" -> R.drawable.ic_flag_sk
    "sl" -> R.drawable.ic_flag_sl
    "sm" -> R.drawable.ic_flag_sm
    "sn" -> R.drawable.ic_flag_sn
    "so" -> R.drawable.ic_flag_so
    "sr" -> R.drawable.ic_flag_sr
    "ss" -> R.drawable.ic_flag_ss
    "st" -> R.drawable.ic_flag_st
    "sv" -> R.drawable.ic_flag_sv
    "sx" -> R.drawable.ic_flag_sx
    "sy" -> R.drawable.ic_flag_sy
    "sz" -> R.drawable.ic_flag_sz
    "tc" -> R.drawable.ic_flag_tc
    "td" -> R.drawable.ic_flag_td
    "tf" -> R.drawable.ic_flag_tf
    "tg" -> R.drawable.ic_flag_tg
    "th" -> R.drawable.ic_flag_th
    "tj" -> R.drawable.ic_flag_tj
    "tk" -> R.drawable.ic_flag_tk
    "tl" -> R.drawable.ic_flag_tl
    "tm" -> R.drawable.ic_flag_tm
    "tn" -> R.drawable.ic_flag_tn
    "to" -> R.drawable.ic_flag_to
    "tr" -> R.drawable.ic_flag_tr
    "tt" -> R.drawable.ic_flag_tt
    "tv" -> R.drawable.ic_flag_tv
    "tw" -> R.drawable.ic_flag_tw
    "tz" -> R.drawable.ic_flag_tz
    "ua" -> R.drawable.ic_flag_ua
    "ug" -> R.drawable.ic_flag_ug
    "um" -> R.drawable.ic_flag_um
    "us" -> R.drawable.ic_flag_us
    "uy" -> R.drawable.ic_flag_uy
    "uz" -> R.drawable.ic_flag_uz
    "va" -> R.drawable.ic_flag_va
    "vc" -> R.drawable.ic_flag_vc
    "ve" -> R.drawable.ic_flag_ve
    "vg" -> R.drawable.ic_flag_vg
    "vi" -> R.drawable.ic_flag_vi
    "vn" -> R.drawable.ic_flag_vn
    "vu" -> R.drawable.ic_flag_vu
    "wf" -> R.drawable.ic_flag_wf
    "ws" -> R.drawable.ic_flag_ws
    "xk" -> R.drawable.ic_flag_xk
    "ye" -> R.drawable.ic_flag_ye
    "yt" -> R.drawable.ic_flag_yt
    "za" -> R.drawable.ic_flag_za
    "zm" -> R.drawable.ic_flag_zm
    "zw" -> R.drawable.ic_flag_zw
    else -> null
}

// Parte da tenere quando la bandiera viene ritagliata in un cerchio (flag_alignments.yml della
// stessa fonte): verso sinistra o destra per le bandiere con il segno distintivo su un lato.
fun flagCropAlignment(countryCode: String): Alignment = when (countryCode.uppercase()) {
    "AW", "BY", "CD", "CF", "CL", "CN", "CU", "CW", "DJ", "ER", "ES", "GR", "GW", "JO", "KM", "LI", "LR", "MH", "MN", "MY", "MZ", "NA", "NR", "NU", "OM", "PH", "PR", "PS", "RS", "SB", "SC", "SD", "SG", "SI", "SK", "SS", "SX", "TG", "TK", "TL", "TM", "TO", "TW", "UY", "UZ", "VU", "WS", "ZW" -> Alignment.CenterStart
    "AI", "AS", "AU", "BM", "CK", "FJ", "FK", "GS", "HM", "IO", "KY", "MS", "NZ", "PK", "PN", "RW", "TC", "TF", "TV", "VG", "ZM" -> Alignment.CenterEnd
    "BH", "CV", "EH", "GL", "GQ", "KP", "PT", "QA", "ST", "UM", "US" -> BiasAlignment(-0.5f, 0f)
    "BT", "CC" -> BiasAlignment(0.5f, 0f)
    else -> Alignment.Center
}

// Bandiera ritagliata in un cerchio con un bordo sottile (le bandiere chiare restano leggibili sullo
// sfondo); [fallback] se il codice manca o non ha una bandiera.
@Composable
fun CountryFlag(countryCode: String?, size: Dp, fallback: @Composable () -> Unit) {
    val flag = countryCode?.let(::flagRes)
    if (countryCode == null || flag == null) {
        fallback()
        return
    }
    Image(
        painter = painterResource(flag),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alignment = flagCropAlignment(countryCode),
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
    )
}
