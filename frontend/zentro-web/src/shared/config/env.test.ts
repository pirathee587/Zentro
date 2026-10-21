import { describe,expect,it } from 'vitest'; import { parseEnvironment } from './env';
describe('parseEnvironment',()=>{it('fails when a required variable is missing',()=>expect(()=>parseEnvironment({VITE_API_BASE_URL:'http://localhost:8080'})).toThrow());});
