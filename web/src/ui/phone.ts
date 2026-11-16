// 11 位手机号显示成 138 0000 5678；别的号码（座机等）原样显示
export const fmtPhone = (p: string) => (/^1\d{10}$/.test(p) ? p.replace(/(\d{3})(\d{4})(\d{4})/, '$1 $2 $3') : p)
