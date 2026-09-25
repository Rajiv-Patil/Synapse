import type * as React from 'react';

declare module 'react' {
    // Compatibility shim for Base Web with React 19
    // eslint-disable-next-line @typescript-eslint/no-empty-object-type
    export type RefForwardingComponent<T, P = {}> = React.ForwardRefRenderFunction<T, P>;
}
