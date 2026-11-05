import { useNavigate } from 'react-router-dom';
import { Button } from '@/shared/ui';
import { ArrowIcon } from '../assets/ArrowIcon';

const Arrow = ArrowIcon;

export function MerchantPage(){const navigate=useNavigate();return <section className="auth-card"><span className="eyebrow">FOR SRI LANKAN BUSINESSES</span><h1>Let's deliver<br/><em>something good.</em></h1><p>Use your existing Zentro account to apply for business access. Once approved, sign in with the same email and password on the merchant page.</p><Button className="auth-submit" onClick={()=>navigate('/register/merchant')}>Create account and apply <Arrow/></Button><Button variant="secondary" onClick={()=>navigate('/merchant/login')}>I already have a Zentro account</Button></section>; }
