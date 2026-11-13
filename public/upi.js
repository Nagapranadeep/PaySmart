export function parseUPI(value){
  let url;try{url=new URL(value.trim())}catch{throw Error('Enter a valid upi://pay link from an Indian UPI QR code.')}
  if(url.protocol!=='upi:'||url.hostname!=='pay')throw Error('This QR is not a UPI payment request.');
  const name=url.searchParams.get('pa')||'';
  if(!/^[a-zA-Z0-9.\-_]{2,100}@[a-zA-Z][a-zA-Z0-9.\-]{1,63}$/.test(name))throw Error('The QR code does not contain a valid UPI ID.');
  if(url.searchParams.has('cu')&&url.searchParams.get('cu')!=='INR')throw Error('Only INR payments are supported.');
  const amount=url.searchParams.get('am');
  if(amount!==null&&(!/^\d+(\.\d{1,2})?$/.test(amount)||Number(amount)<1||Number(amount)>100000))throw Error('The QR amount must be between ₹1 and ₹1,00,000.');
  return {name,payeeName:(url.searchParams.get('pn')||'').slice(0,80),amount:amount?Number(amount):'',note:(url.searchParams.get('tn')||'').slice(0,100)};
}
