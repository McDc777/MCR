const ev = (str) => {
    str = String(str).replace(/\s+/g, "").replace(/[+\-*/.]+$/, "");
    if (str === "") return 0;
    if (!/^[-+*/.\d]+$/.test(str)) return null;
    const nums = [], ops = []; let i = 0, wantNum = true;
    while (i < str.length) {
      let c = str[i];
      if (!wantNum && "+-*/".includes(c)) { ops.push(c); i++; wantNum = true; continue; }
      if (wantNum) {
        let sign = 1;
        while (i < str.length && (str[i] === "+" || str[i] === "-")) { if (str[i] === "-") sign *= -1; i++; }
        let start = i;
        while (i < str.length && /[\d.]/.test(str[i])) i++;
        if (i === start) return null;
        const n = parseFloat(str.slice(start, i)); if (isNaN(n)) return null;
        nums.push(sign * n); wantNum = false;
      }
    }
    if (wantNum) return null;
    for (let k = 0; k < ops.length;) { if (ops[k] === "*" || ops[k] === "/") { const r = ops[k] === "*" ? nums[k] * nums[k + 1] : nums[k] / nums[k + 1]; nums.splice(k, 2, r); ops.splice(k, 1); } else k++; }
    let acc = nums[0]; for (let k = 0; k < ops.length; k++) acc = ops[k] === "+" ? acc + nums[k + 1] : acc - nums[k + 1];
    return isFinite(acc) ? Math.round(acc * 1e10) / 1e10 : null;
};
if (typeof module !== 'undefined' && module.exports) {
    module.exports = { ev };
}
