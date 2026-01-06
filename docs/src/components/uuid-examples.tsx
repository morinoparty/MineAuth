import { v7 as uuidv7, v4 as uuidv4 } from 'uuid';

export const UUIDV7 = () => {
    return (
        <>
            {uuidv7()}
        </>
    );
};

export const UUIDV4 = () => {
    return (
        <>
            {uuidv4()}
        </>
    );
};