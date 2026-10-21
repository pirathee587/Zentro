import { Component, type ReactNode } from 'react'; import { ErrorState } from '@/shared/ui';
export class ErrorBoundary extends Component<{children:ReactNode},{error:boolean}>{state={error:false};static getDerivedStateFromError(){return {error:true};}componentDidCatch(){}render(){return this.state.error?<ErrorState/>:this.props.children;}}
