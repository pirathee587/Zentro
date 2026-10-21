import js from '@eslint/js';
import globals from 'globals';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';
import tseslint from 'typescript-eslint';

const rawControlRule = ['error', {
  selector: "JSXOpeningElement[name.name=/^(button|input|select|textarea)$/]",
  message: 'Use the corresponding component from @/shared/ui instead of a raw control.',
}];
const portals = ['public', 'customer', 'merchant', 'courier', 'admin'];
const portalRules = Object.fromEntries(portals.map((portal) => [`src/portals/${portal}/**/*.{ts,tsx}`, {
  rules: {'no-restricted-imports':['error',{patterns:[{group:portals.filter((other) => other !== portal).flatMap((other) => [`@/portals/${other}`,`@/portals/${other}/**`]),message:'A portal must not import another portal.'}]}], 'no-restricted-syntax':rawControlRule}
}]));

export default [
  {ignores:['dist/**','coverage/**','node_modules/**','*.d.ts']}, js.configs.recommended, ...tseslint.configs.recommended,
  {files:['**/*.{ts,tsx}'], languageOptions:{ecmaVersion:2022,globals:{...globals.browser}}, plugins:{'react-hooks':reactHooks,'react-refresh':reactRefresh}, rules:{...reactHooks.configs.recommended.rules,'react-refresh/only-export-components':['warn',{allowConstantExport:true}]}},
  ...Object.entries(portalRules).map(([files, config]) => ({files:[files], ...config})),
  {files:['src/shared/**/*.{ts,tsx}'], rules:{'no-restricted-imports':['error',{patterns:[{group:['@/portals/**','@/app/**'],message:'Shared modules must not import portals or app.'}]}], 'no-restricted-syntax':rawControlRule}},
  {files:['src/app/**/*.{ts,tsx}'], rules:{'no-restricted-syntax':rawControlRule}},
  {files:['src/shared/ui/**/*.{ts,tsx}'], rules:{'no-restricted-syntax':'off'}},
];
